import { expect, test, type Page, type Route } from '@playwright/test';

// Every /api call is mocked; no backend is needed.
const CENTER = { id: 'c1', name: 'Center One', nameAr: 'المركز الأول', code: 'C1' };
const VOLUME = {
  from: '2025-01-01', to: '2025-01-31', groupBy: 'status', total: 7,
  groups: [{ key: 'completed', label: 'Completed', count: 5 }, { key: 'new', label: 'New', count: 2 }],
};

const json = (route: Route, body: unknown, status = 200) =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

async function signIn(page: Page, role: string, lang = 'ar') {
  await page.addInitScript(([r, l]) => {
    localStorage.setItem('smms_token', 'e2e-token');
    localStorage.setItem('smms_user', JSON.stringify({ id: 'u1', username: 'e2e', fullName: 'E2E', role: r, active: true }));
    localStorage.setItem('smms_lang', l);
  }, [role, lang]);
}

test.describe('reports', () => {
  let authHeaders: (string | undefined)[];
  let reportQueries: string[];

  test.beforeEach(async ({ page }) => {
    authHeaders = [];
    reportQueries = [];
    await page.route('**/api/**', async (route) => {
      const req = route.request();
      const url = new URL(req.url());
      const p = url.pathname.replace(/^\/api/, '');
      if (p === '/admin/centers') return json(route, [CENTER]);
      if (p === '/admin/regions') return json(route, []);
      if (p === '/notifications/unread-count') return json(route, 0);
      if (p.startsWith('/reports/')) {
        reportQueries.push(`${p}${url.search}`);
        if (url.searchParams.get('format') === 'csv') {
          authHeaders.push(req.headers()['authorization']);
          return route.fulfill({
            status: 200,
            contentType: 'text/csv; charset=UTF-8',
            headers: { 'content-disposition': 'attachment; filename="work-order-volume-2025-01-01-2025-01-30.csv"' },
            body: '﻿"group","count"\n"completed","5"\n',
          });
        }
        if (p === '/reports/work-orders/volume') return json(route, VOLUME);
        if (p === '/reports/escalations') return json(route, { from: '2025-01-01', to: '2025-01-31', available: false, groups: [], byDay: [] });
        if (p === '/reports/sla') return json(route, [{ priority: 'high', due: 10, metOnTime: 8, breached: 2, compliancePct: 80, avgPausedHours: 1.5, openBreached: 1 }]);
        if (p.startsWith('/reports/inventory/')) return json(route, { name: 'stock', from: null, to: null, columns: ['center', 'item', 'available'], rows: [['C1', 'Filter', 4]] });
        return json(route, []);
      }
      return json(route, {});
    });
  });

  test('shows volume chart and table, then validates the date range (max 366 days)', async ({ page }) => {
    await signIn(page, 'hospital_supervisor');
    await page.goto('/reports');
    await expect(page.getByRole('heading', { name: 'التقارير', level: 1 })).toBeVisible();
    await expect(page.getByRole('list', { name: /عدد البلاغات/ })).toBeVisible();
    await expect(page.getByRole('table')).toContainText('مكتمل');

    await page.getByLabel('من تاريخ').fill('2023-01-01');
    await page.getByLabel('إلى تاريخ').fill('2024-06-01');
    const before = reportQueries.length;
    await page.getByRole('button', { name: 'تطبيق' }).click();
    await expect(page.getByRole('alert').filter({ hasText: 'المدة تتجاوز 366 يوماً' })).toBeVisible();
    expect(reportQueries.length).toBe(before);

    await page.getByLabel('إلى تاريخ').fill('2023-12-31');
    await page.getByRole('button', { name: 'تطبيق' }).click();
    await expect.poll(() => reportQueries.some((q) => q.includes('from=2023-01-01') && q.includes('to=2023-12-31'))).toBe(true);
  });

  test('CSV download sends the Bearer token and saves the server filename', async ({ page }) => {
    await signIn(page, 'hospital_supervisor');
    await page.goto('/reports');
    await expect(page.getByRole('list', { name: /عدد البلاغات/ })).toBeVisible();
    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: 'تنزيل CSV' }).click();
    const file = await download;
    expect(file.suggestedFilename()).toBe('work-order-volume-2025-01-01-2025-01-30.csv');
    expect(authHeaders[0]).toBe('Bearer e2e-token');
    await expect(page.getByText(/تم تنزيل الملف/)).toBeVisible();
  });

  test('escalations without data explain themselves; SLA tab renders', async ({ page }) => {
    await signIn(page, 'hospital_supervisor');
    await page.goto('/reports');
    await page.getByRole('tab', { name: 'التصعيدات' }).click();
    await expect(page.getByText(/بيانات التصعيد غير متاحة/)).toBeVisible();
    await page.getByRole('tab', { name: 'اتفاقية الخدمة SLA' }).click();
    await expect(page.getByRole('table')).toContainText('80');
  });

  test('English LTR render for a warehouse user: inventory tab only', async ({ page }) => {
    await signIn(page, 'warehouse', 'en');
    await page.goto('/reports');
    await expect(page.getByRole('heading', { name: 'Reports', level: 1 })).toBeVisible();
    await expect(page.getByRole('tab')).toHaveCount(1);
    await expect(page.getByRole('tab', { name: 'Inventory and purchasing' })).toBeVisible();
    await expect(page.getByRole('table')).toContainText('Filter');
    await expect(page.locator('div[dir]').first()).toHaveAttribute('dir', 'ltr');
  });
});
