import { expect, test, type Page, type Route } from '@playwright/test';

// All /api calls are mocked with page.route; no backend is needed.
type Handler = (route: Route, body: unknown) => unknown | Promise<unknown>;
const json = (route: Route, status: number, body: unknown, headers: Record<string, string> = {}) =>
  route.fulfill({ status, contentType: 'application/json', headers, body: JSON.stringify(body) });
const pg = <T,>(content: T[]) => ({ content, number: 0, size: 20, totalElements: content.length, totalPages: 1 });

async function boot(page: Page, role: string, handlers: Record<string, Handler>, lang: 'ar' | 'en' = 'en') {
  const user = { id: 'u-1', username: 'tester', fullName: 'Tester', role, active: true, center: { id: 'c-1', name: 'Center 1', nameAr: 'مركز 1', code: 'C1' } };
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

const WH = { id: 'w-1', centerId: 'c-1', centerName: 'Center 1', code: 'WH1', name: 'Main store', nameAr: 'المستودع الرئيسي', active: true };
const ITEM = { id: 'i-1', sku: 'SKU-1', name: 'Air filter', nameAr: 'فلتر هواء', category: 'HVAC', uom: 'pcs', reorderLevel: 5, totalOnHand: 3, totalReserved: 1, totalAvailable: 2, lowStock: true, active: true, center: { id: 'c-1', name: 'Center 1' } };

test('warehouse: items list shows low stock, receipt posts with an Idempotency-Key and reuses it on retry', async ({ page }) => {
  const keys: (string | undefined)[] = [];
  let calls = 0;
  await boot(page, 'warehouse', {
    'GET /inventory/items': (r) => json(r, 200, pg([ITEM])),
    'GET /inventory/warehouses': (r) => json(r, 200, pg([WH])),
    'POST /inventory/receipts': (r) => {
      keys.push(r.request().headers()['idempotency-key']);
      calls += 1;
      if (calls === 1) return json(r, 409, { status: 409, message: 'The system is busy, retry in a moment' });
      return json(r, 201, { movements: [{ id: 'm-1' }] });
    },
  });
  await page.goto('/inventory');
  await expect(page.getByRole('link', { name: 'Air filter' })).toBeVisible();
  await expect(page.getByText('Low stock', { exact: true })).toBeVisible();
  await page.getByRole('link', { name: 'Goods receipt' }).first().click();
  await expect(page).toHaveURL(/\/inventory\/receipts\/new/);

  await page.getByLabel(/^Warehouse/).selectOption('w-1');
  await page.getByRole('combobox', { name: /Item 1/ }).selectOption('i-1');
  await page.getByLabel(/^Quantity/).fill('12');
  await page.getByRole('button', { name: 'Post receipt' }).click();
  await expect(page.getByRole('alert').filter({ hasText: 'The system is busy' })).toBeVisible();
  await page.getByRole('button', { name: 'Post receipt' }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Receipt posted' })).toBeVisible();

  expect(keys).toHaveLength(2);
  expect(keys[0]).toMatch(/^[A-Za-z0-9-]{8,64}$/);
  expect(keys[1]).toBe(keys[0]);
});

test('manager: adjustment needs a reason and surfaces a 409 insufficient stock message', async ({ page }) => {
  await boot(page, 'maintenance_manager', {
    'GET /inventory/warehouses': (r) => json(r, 200, pg([WH])),
    'GET /inventory/items': (r) => json(r, 200, pg([ITEM])),
    'POST /inventory/adjustments': (r, b) => {
      expect(b).toMatchObject({ direction: 'out', reasonCode: 'damage', note: 'water damage' });
      return json(r, 409, { status: 409, message: 'Insufficient stock for SKU-1: available 2' });
    },
  });
  await page.goto('/inventory/adjustments/new');
  await page.getByLabel(/^Warehouse/).selectOption('w-1');
  await page.getByLabel(/^Item/).selectOption('i-1');
  await page.getByLabel(/Direction/).selectOption('out');
  await page.getByLabel(/^Quantity/).fill('9');
  await page.getByLabel(/Reason type/).selectOption('damage');
  await page.getByRole('button', { name: 'Post adjustment' }).click();
  await expect(page.getByText('Write a reason of at least 5 characters')).toBeVisible();
  await page.getByRole('textbox', { name: /^Reason/ }).fill('water damage');
  await page.getByRole('button', { name: 'Post adjustment' }).click();
  await expect(page.getByRole('alert').filter({ hasText: 'Insufficient stock for SKU-1' })).toBeVisible();
});

test('manager: movements history and reversal with a reason', async ({ page }) => {
  let reverseBody: unknown;
  await boot(page, 'maintenance_manager', {
    'GET /inventory/warehouses': (r) => json(r, 200, pg([WH])),
    'GET /inventory/movements': (r) =>
      json(r, 200, pg([{ id: 'm-1', kind: 'receipt', item: { id: 'i-1', sku: 'SKU-1', name: 'Air filter' }, warehouse: { id: 'w-1', code: 'WH1', name: 'Main store' }, quantity: 5, balanceAfter: 5, createdAt: '2026-10-01T10:00:00Z' }])),
    'POST /inventory/movements/m-1/reverse': (r, b) => {
      reverseBody = b;
      return json(r, 201, { id: 'm-2' });
    },
  });
  await page.goto('/inventory/movements');
  await expect(page.getByRole('cell', { name: 'Receipt' })).toBeVisible();
  await page.getByRole('button', { name: 'Reverse' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('button', { name: 'Reverse' }).click();
  await expect(dialog.getByText('Write a reason of at least 5 characters')).toBeVisible();
  await dialog.getByRole('textbox').fill('received twice by mistake');
  await dialog.getByRole('button', { name: 'Reverse' }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Movement reversed.' })).toBeVisible();
  expect(reverseBody).toEqual({ reason: 'received twice by mistake' });
});

test('technician is told access is denied on the receipt page; 403 from the API shows PermissionDenied', async ({ page }) => {
  await boot(page, 'technician', {
    'GET /inventory/movements': (r) => json(r, 403, { status: 403, message: 'Access denied' }),
  });
  await page.goto('/inventory/receipts/new');
  await expect(page.getByRole('alert').filter({ hasText: 'Access denied' })).toBeVisible();
  await page.goto('/inventory/movements');
  await expect(page.getByRole('alert').filter({ hasText: 'Access denied' })).toBeVisible();
});

test('Arabic is the default: RTL layout and Arabic headings', async ({ page }) => {
  await boot(page, 'warehouse', { 'GET /inventory/items': (r) => json(r, 200, pg([ITEM])) }, 'ar');
  await page.goto('/inventory');
  await expect(page.getByRole('heading', { name: 'الأصناف' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'فلتر هواء' })).toBeVisible();
  await expect(page.locator('div[dir="rtl"]').first()).toBeVisible();
});
