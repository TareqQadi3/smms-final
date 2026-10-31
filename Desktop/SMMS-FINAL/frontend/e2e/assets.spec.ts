import { expect, test, type Page, type Route } from '@playwright/test';

// Every /api call is mocked; no backend is needed.
const CENTER = { id: 'c1', name: 'Center One', nameAr: 'المركز الأول', code: 'C1' };
const ASSET = {
  id: 'a1', assetCode: 'AST-001', name: 'Chiller', nameAr: 'مبرد', category: 'HVAC', manufacturer: null, model: null,
  serialNumber: null, location: 'Roof', status: 'active', criticality: 'high', installDate: '2024-01-10', warrantyExpiry: null,
  decommissionedAt: null, center: CENTER, department: null, openWorkOrders: 2, activeDowntime: false, version: 3,
};
const page_ = <T,>(content: T[]) => ({ content, number: 0, size: 20, totalElements: content.length, totalPages: 1 });

async function signIn(page: Page, role: string) {
  await page.addInitScript((r) => {
    localStorage.setItem('smms_token', 'e2e-token');
    localStorage.setItem('smms_user', JSON.stringify({ id: 'u1', username: 'e2e', fullName: 'E2E', role: r, active: true }));
    localStorage.setItem('smms_lang', 'ar');
  }, role);
}

const json = (route: Route, body: unknown, status = 200) =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

test.describe('assets', () => {
  let queries: string[];
  let putBodies: Record<string, unknown>[];
  let putStatus: number;

  test.beforeEach(async ({ page }) => {
    queries = [];
    putBodies = [];
    putStatus = 200;
    await signIn(page, 'hospital_supervisor');
    await page.route('**/api/**', async (route) => {
      const req = route.request();
      const url = new URL(req.url());
      const p = url.pathname.replace(/^\/api/, '');
      if (p === '/admin/centers') return json(route, [CENTER]);
      if (p === '/departments') return json(route, page_([]));
      if (p === '/notifications/unread-count') return json(route, 0);
      if (p === '/notifications') return json(route, page_([]));
      if (p === '/assets' && req.method() === 'GET') {
        queries.push(url.search);
        return json(route, page_([ASSET]));
      }
      if (p === '/assets' && req.method() === 'POST') return json(route, { ...ASSET, id: 'a9', name: 'Pump' }, 201);
      if (p === '/assets/a1' && req.method() === 'GET') return json(route, { ...ASSET, name: 'Chiller 2', version: putStatus === 409 ? 4 : 3 });
      if (p === '/assets/a1' && req.method() === 'PUT') {
        putBodies.push(req.postDataJSON());
        if (putStatus === 409) return json(route, { status: 409, message: 'The record was modified concurrently, reload and try again' }, 409);
        return json(route, ASSET);
      }
      if (p === '/assets/a9') return json(route, { ...ASSET, id: 'a9', name: 'Pump', nameAr: 'مضخة' });
      if (/\/assets\/(a1|a9)\/(work-orders|downtime)$/.test(p)) return json(route, page_([]));
      if (/\/assets\/(a1|a9)\/materials$/.test(p)) return json(route, []);
      return json(route, {});
    });
  });

  test('register is Arabic RTL, filters by text and opens the detail with its tabs', async ({ page }) => {
    await page.goto('/assets');
    await expect(page.getByRole('heading', { name: 'سجل الأصول', level: 1 })).toBeVisible();
    await expect(page.locator('div[dir]').first()).toHaveAttribute('dir', 'rtl');
    await expect(page.getByRole('link', { name: 'مبرد' })).toBeVisible();
    await page.getByRole('searchbox').fill('chill');
    await page.getByRole('button', { name: 'تصفية' }).click();
    await expect.poll(() => queries.some((q) => q.includes('q=chill'))).toBe(true);

    await page.getByRole('link', { name: 'مبرد' }).click();
    await expect(page.getByRole('heading', { name: 'مبرد', level: 1 })).toBeVisible();
    await page.getByRole('tab', { name: 'سجل البلاغات' }).click();
    await expect(page.getByText('لا توجد بلاغات لهذا الأصل.')).toBeVisible();
    await page.getByRole('tab', { name: 'التوقفات' }).click();
    await expect(page.getByRole('button', { name: 'تسجيل توقف' })).toBeVisible();
  });

  test('create shows validation first, then redirects to the new asset', async ({ page }) => {
    await page.goto('/assets/new');
    await page.getByRole('button', { name: 'حفظ' }).click();
    await expect(page.getByText('الاسم الإنجليزي مطلوب')).toBeVisible();
    await page.getByLabel('الاسم (عربي)').fill('مضخة');
    await page.getByLabel('الاسم (إنجليزي)').fill('Pump');
    await page.getByRole('button', { name: 'حفظ' }).click();
    await expect(page).toHaveURL(/\/assets\/a9$/);
    await expect(page.getByRole('heading', { name: 'مضخة', level: 1 })).toBeVisible();
  });

  test('edit: a 409 shows the reload message and reload loads the new version', async ({ page }) => {
    await page.goto('/assets/a1/edit');
    await expect(page.getByLabel('الاسم (إنجليزي)')).toHaveValue('Chiller 2');
    putStatus = 409;
    await page.getByRole('button', { name: 'حفظ' }).click();
    await expect(page.getByText(/تم تعديل هذا الأصل من مستخدم آخر/)).toBeVisible();
    expect(putBodies[0]).toMatchObject({ version: 3 });
    await page.getByRole('button', { name: 'إعادة تحميل' }).click();
    await expect(page.getByText('تم تحميل أحدث نسخة.')).toBeVisible();
    putStatus = 200;
    await page.getByRole('button', { name: 'حفظ' }).click();
    await expect.poll(() => putBodies.length).toBe(2);
    expect(putBodies[1]).toMatchObject({ version: 4 });
  });

  test('a read-only role sees no add button', async ({ page }) => {
    await page.addInitScript(() => {
      const u = JSON.parse(localStorage.getItem('smms_user') ?? '{}');
      localStorage.setItem('smms_user', JSON.stringify({ ...u, role: 'control_room' }));
    });
    await page.goto('/assets');
    await expect(page.getByRole('link', { name: 'مبرد' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'إضافة أصل' })).toHaveCount(0);
  });
});
