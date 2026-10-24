import { expect, test, type Page, type Route } from '@playwright/test';

// All /api calls are mocked with page.route; no backend is needed.
type Handler = (route: Route, body: unknown) => unknown | Promise<unknown>;
const json = (route: Route, status: number, body: unknown) =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
const pg = <T,>(content: T[]) => ({ content, number: 0, size: 20, totalElements: content.length, totalPages: 1 });

async function boot(page: Page, role: string, handlers: Record<string, Handler>, userId = 'u-1', lang: 'ar' | 'en' = 'en') {
  const user = { id: userId, username: 'tester', fullName: 'Tester', role, active: true, center: { id: 'c-1', name: 'Center 1', nameAr: 'مركز 1', code: 'C1' } };
  await page.addInitScript(([u, l]) => {
    localStorage.setItem('smms_token', 'test-token');
    localStorage.setItem('smms_user', u as string);
    localStorage.setItem('smms_lang', l as string);
  }, [JSON.stringify(user), lang]);
  await page.route('**/api/**', async (route) => {
    const req = route.request();
    const path = new URL(req.url()).pathname.replace(/^\/api/, '');
    const h = handlers[`${req.method()} ${path}`];
    if (!h) return json(route, 200, {});
    let body: unknown = undefined;
    try { body = req.postDataJSON(); } catch { /* no body */ }
    await h(route, body);
  });
}

const SUP = { id: 's-1', code: 'S1', name: 'Acme', nameAr: 'أكمي', active: true };
const PO = (over: Record<string, unknown> = {}) => ({
  id: 'po-1', poNumber: 'PO-0001', status: 'submitted', supplier: SUP, center: { id: 'c-1', name: 'Center 1' }, warehouse: { id: 'w-1', code: 'WH1', name: 'Main store' },
  createdBy: { id: 'creator', fullName: 'Creator' }, version: 2,
  lines: [{ id: 'l-1', lineNo: 1, item: { id: 'i-1', sku: 'SKU-1', name: 'Air filter' }, qtyOrdered: 10, qtyReceived: 4, unitPrice: 2.5 }],
  receipts: [], ...over,
});
const MR = (over: Record<string, unknown> = {}) => ({
  id: 'mr-1', requestNumber: 'MR-0001', status: 'approved', createdAt: '2026-10-01T10:00:00Z', version: 5,
  workOrder: { id: 'wo-1', ticketNumber: 'WO-1', status: 'in_progress' }, requestedBy: { id: 'tech', fullName: 'Tech' }, warehouse: { id: 'w-1', code: 'WH1', name: 'Main store' },
  lines: [{ id: 'ml-1', item: { id: 'i-1', sku: 'SKU-1', name: 'Air filter' }, qtyRequested: 5, qtyApproved: 5, qtyReserved: 0, qtyIssued: 0, qtyConsumed: 0, qtyReturned: 0 }], ...over,
});

test('OCM approves a submitted purchase order; the creator cannot', async ({ page }) => {
  let approveBody: unknown;
  await boot(page, 'operating_company_manager', {
    'GET /procurement/purchase-orders/po-1': (r) => json(r, 200, PO()),
    'POST /procurement/purchase-orders/po-1/approve': (r, b) => {
      approveBody = b;
      return json(r, 200, PO({ status: 'approved', approvedBy: { id: 'u-1', fullName: 'Tester' } }));
    },
  });
  await page.goto('/procurement/purchase-orders/po-1');
  await expect(page.getByRole('heading', { name: /PO-0001/ })).toBeVisible();
  await page.getByRole('button', { name: 'Approve' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Approve' }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Purchase order approved.' })).toBeVisible();
  expect(approveBody).toMatchObject({ version: 2 });
});

test('the creator sees no approve button (separation of duties)', async ({ page }) => {
  await boot(page, 'maintenance_manager', { 'GET /procurement/purchase-orders/po-1': (r) => json(r, 200, PO()) }, 'creator');
  await page.goto('/procurement/purchase-orders/po-1');
  await expect(page.getByRole('heading', { name: /PO-0001/ })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Approve' })).toHaveCount(0);
});

test('warehouse: over-receipt 422 is surfaced, then a valid receipt succeeds with the retained key', async ({ page }) => {
  const keys: (string | undefined)[] = [];
  const bodies: unknown[] = [];
  let calls = 0;
  await boot(page, 'warehouse', {
    'GET /procurement/purchase-orders/po-1': (r) => json(r, 200, PO({ status: 'partially_received' })),
    'POST /procurement/purchase-orders/po-1/receive': (r, b) => {
      keys.push(r.request().headers()['idempotency-key']);
      bodies.push(b);
      calls += 1;
      if (calls === 1) return json(r, 422, { status: 422, message: 'Over-receipt: ordered 10, already received 4, requested 9' });
      return json(r, 201, { id: 'rc-1', lines: [] });
    },
  });
  await page.goto('/procurement/purchase-orders/po-1');
  await page.getByRole('button', { name: 'Receive goods' }).click();
  const dialog = page.getByRole('dialog');
  const qty = dialog.getByRole('textbox', { name: /SKU-1/ });
  await expect(qty).toHaveValue('6');
  await qty.fill('9');
  await dialog.getByRole('button', { name: 'Receive goods' }).click();
  await expect(dialog.getByRole('alert').filter({ hasText: 'Over-receipt' })).toBeVisible();
  await qty.fill('6');
  await dialog.getByRole('button', { name: 'Receive goods' }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Receipt recorded and stock updated.' })).toBeVisible();
  expect(keys[0]).toMatch(/^[A-Za-z0-9-]{8,64}$/);
  expect(keys[1]).not.toBe(keys[0]); // different quantity = new intent = new key
  expect(bodies[1]).toMatchObject({ lines: [{ poLineId: 'l-1', quantity: 6 }] });
});

test('warehouse creates a purchase order draft from the form', async ({ page }) => {
  let created: unknown;
  await boot(page, 'warehouse', {
    'GET /procurement/suppliers': (r) => json(r, 200, pg([SUP])),
    'GET /inventory/warehouses': (r) => json(r, 200, pg([{ id: 'w-1', centerId: 'c-1', code: 'WH1', name: 'Main store', active: true }])),
    'GET /inventory/items': (r) => json(r, 200, pg([{ id: 'i-1', sku: 'SKU-1', name: 'Air filter', active: true }])),
    'POST /procurement/purchase-orders': (r, b) => {
      created = b;
      return json(r, 201, PO({ status: 'draft' }));
    },
    'GET /procurement/purchase-orders/po-1': (r) => json(r, 200, PO({ status: 'draft' })),
  });
  await page.goto('/procurement/purchase-orders/new');
  await page.getByRole('button', { name: 'Save as draft' }).click();
  await expect(page.getByRole('alert').first()).toBeVisible();
  await page.getByLabel(/^Warehouse/).selectOption('w-1');
  await page.getByLabel(/^Supplier/).selectOption('s-1');
  await page.getByRole('combobox', { name: /Item 1/ }).selectOption('i-1');
  await page.getByLabel(/Qty ordered/).fill('10');
  await page.getByLabel(/Unit price/).fill('2.5');
  await page.getByRole('button', { name: 'Save as draft' }).click();
  await expect(page).toHaveURL(/\/procurement\/purchase-orders\/po-1$/);
  expect(created).toMatchObject({ centerId: 'c-1', supplierId: 's-1', warehouseId: 'w-1', lines: [{ itemId: 'i-1', qtyOrdered: 10, unitPrice: 2.5 }] });
});

test('warehouse reserves a material request with a key; a 409 shows insufficient stock', async ({ page }) => {
  const keys: (string | undefined)[] = [];
  let calls = 0;
  await boot(page, 'warehouse', {
    'GET /material-requests/mr-1': (r) => json(r, 200, MR()),
    'POST /material-requests/mr-1/reserve': (r) => {
      keys.push(r.request().headers()['idempotency-key']);
      calls += 1;
      if (calls === 1) return json(r, 409, { status: 409, message: 'Insufficient stock for SKU-1: available 2' });
      return json(r, 200, MR({ status: 'reserved' }));
    },
  });
  await page.goto('/material-requests/mr-1');
  await expect(page.getByRole('heading', { name: /MR-0001/ })).toBeVisible();
  await page.getByRole('button', { name: 'Reserve' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Reserve' }).click();
  await expect(page.getByRole('alert').filter({ hasText: 'Insufficient stock for SKU-1' })).toBeVisible();
  await page.getByRole('button', { name: 'Reserve' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Reserve' }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Materials reserved.' })).toBeVisible();
  expect(keys[1]).toBe(keys[0]);
  await expect(page.getByRole('button', { name: 'Issue' })).toBeVisible();
});

test('technician sees the request list in Arabic RTL and no warehouse actions on the detail page', async ({ page }) => {
  await boot(
    page,
    'technician',
    {
      'GET /material-requests': (r) => json(r, 200, pg([{ id: 'mr-1', requestNumber: 'MR-0001', status: 'approved', workOrder: { id: 'wo-1', ticketNumber: 'WO-1', status: 'in_progress' }, createdAt: '2026-10-01T10:00:00Z' }])),
      'GET /material-requests/mr-1': (r) => json(r, 200, MR()),
    },
    'tech',
    'ar'
  );
  await page.goto('/material-requests');
  await expect(page.getByRole('heading', { name: 'طلبات المواد' })).toBeVisible();
  await expect(page.locator('div[dir="rtl"]').first()).toBeVisible();
  await page.getByRole('link', { name: 'MR-0001' }).click();
  await expect(page.getByRole('heading', { name: /MR-0001/ })).toBeVisible();
  await expect(page.getByRole('button', { name: 'حجز' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'صرف' })).toHaveCount(0);
});
