import { expect, test } from '@playwright/test';
import { json, page0, signIn, wo } from './helpers';

test.beforeEach(async ({ page }) => {
  await page.routeWebSocket(/ws\/notifications/, (ws) => ws.close());
});

test('two parallel calls with an expired access token produce ONE refresh, then work', async ({ page }) => {
  await signIn(page);
  let refreshes = 0;
  const seen: string[] = [];
  await page.route('**/api/**', async (route) => {
    const req = route.request();
    const p = new URL(req.url()).pathname.replace(/^\/api/, '');
    if (p === '/auth/refresh') {
      refreshes++;
      expect(req.headers()['x-refresh-token']).toBe('ref-1');
      await new Promise((r) => setTimeout(r, 300));
      return json(route, 200, { token: 'acc-2', refreshToken: 'ref-2', role: 'technician' });
    }
    if (p === '/notifications/unread-count' || p === '/ws/ticket') {
      return json(route, 200, p === '/ws/ticket' ? { ticket: 't' } : 0);
    }
    seen.push(`${p}:${req.headers()['authorization']}`);
    if (req.headers()['authorization'] !== 'Bearer acc-2') return json(route, 401, { message: 'expired' });
    if (p === '/work-orders') return json(route, 200, page0([wo()]));
    return json(route, 404, {});
  });
  await page.goto('/tech');
  await expect(page.getByTestId('wo-card').first()).toBeVisible();
  expect(refreshes).toBe(1);
  expect(await page.evaluate(() => localStorage.getItem('smms_refresh_token'))).toBe('ref-2');
});

test('a rejected refresh logs out and returns to the intended page after login', async ({ page }) => {
  await signIn(page);
  await page.route('**/api/**', async (route) => {
    const p = new URL(route.request().url()).pathname.replace(/^\/api/, '');
    if (p === '/auth/refresh') return json(route, 401, { message: 'Invalid token' });
    if (p === '/auth/login') return json(route, 200, { token: 'n1', refreshToken: 'n2', role: 'technician', userId: 'u-tech', username: 'tech1', fullName: 'Tech One' });
    if (p === '/auth/logout') return route.fulfill({ status: 204 });
    if (p === '/work-orders/w1' && route.request().headers()['authorization'] === 'Bearer n1') return json(route, 200, wo());
    if (p === '/notifications/unread-count') return json(route, 200, 0);
    return json(route, 401, { message: 'expired' });
  });
  await page.goto('/tech/w1');
  await page.waitForURL(/\/login\?/);
  expect(page.url()).toContain('expired=1');
  expect(decodeURIComponent(page.url())).toContain('next=/tech/w1');
  await expect(page.getByText('انتهت جلستك')).toBeVisible();
  expect(await page.evaluate(() => localStorage.getItem('smms_token'))).toBeNull();
  expect(await page.evaluate(() => localStorage.getItem('smms_refresh_token'))).toBeNull();

  await page.getByLabel('اسم المستخدم').fill('tech1');
  await page.getByLabel('كلمة المرور', { exact: true }).fill('secret');
  await page.getByRole('button', { name: 'تسجيل الدخول' }).click();
  await page.waitForURL('**/tech/w1');
});

test('logout calls the API with both tokens', async ({ page }) => {
  await signIn(page);
  let logoutHeaders: Record<string, string> | null = null;
  await page.route('**/api/**', async (route) => {
    const p = new URL(route.request().url()).pathname.replace(/^\/api/, '');
    if (p === '/auth/logout') { logoutHeaders = route.request().headers(); return route.fulfill({ status: 204 }); }
    if (p === '/work-orders') return json(route, 200, page0([]));
    if (p === '/notifications/unread-count') return json(route, 200, 0);
    return json(route, 200, {});
  });
  await page.goto('/tech');
  await page.getByRole('button', { name: 'تسجيل الخروج' }).click();
  await page.waitForURL('**/login');
  await expect.poll(() => logoutHeaders).not.toBeNull();
  expect(logoutHeaders!['authorization']).toBe('Bearer acc-1');
  expect(logoutHeaders!['x-refresh-token']).toBe('ref-1');
  expect(await page.evaluate(() => localStorage.getItem('smms_refresh_token'))).toBeNull();
});

test('public status page: no auth header, whitelisted fields, and a neutral 404', async ({ page }) => {
  let auth: string | undefined;
  await page.route('**/api/public/request-status/**', async (route) => {
    auth = route.request().headers()['authorization'];
    if (route.request().url().endsWith('/good')) {
      return json(route, 200, { ticketNumber: 'WO-7', status: 'in_progress', statusGroup: 'in_progress', createdAt: '2026-01-01T08:00:00Z', lastUpdatedAt: '2026-01-02T08:00:00Z', completed: false });
    }
    return json(route, 404, { status: 404, message: 'Not found' });
  });
  await page.addInitScript(() => localStorage.setItem('smms_token', 'should-not-be-sent'));
  await page.goto('/status/good');
  await expect(page.getByTestId('public-status')).toContainText('WO-7');
  expect(auth).toBeUndefined();
  await page.goto('/status/nope');
  await expect(page.getByRole('alert').filter({ hasText: 'الرابط غير صالح' })).toBeVisible();
});

test.describe('requester', () => {
  test('confirm the finished work, then the rating form appears', async ({ page }) => {
    await page.addInitScript(() => {
      if (sessionStorage.getItem('e2e-seeded')) return;
      sessionStorage.setItem('e2e-seeded', '1');
      localStorage.setItem('smms_token', 'a');
      localStorage.setItem('smms_refresh_token', 'r');
      localStorage.setItem('smms_user', JSON.stringify({ id: 'u-res', username: 'res', fullName: 'Res', role: 'resident', active: true }));
    });
    let confirmed = false;
    let key: string | undefined;
    await page.route('**/api/**', async (route) => {
      const req = route.request();
      const p = new URL(req.url()).pathname.replace(/^\/api/, '');
      const w = wo({ status: 'completed', techReport: 'Fixed', requesterConfirmation: confirmed ? 'accepted' : null, requesterConfirmedAt: confirmed ? '2026-01-03T00:00:00Z' : null, completedDate: new Date().toISOString() });
      if (p === '/work-orders/w1') return json(route, 200, w);
      if (p === '/work-orders/w1/history') return json(route, 200, []);
      if (p === '/work-orders/w1/confirm') { confirmed = true; key = req.headers()['idempotency-key']; return json(route, 200, w); }
      if (p === '/work-orders/w1/rating') return json(route, 404, { message: 'none' });
      if (p === '/notifications/unread-count') return json(route, 200, 0);
      return json(route, 200, {});
    });
    await page.goto('/my-requests/w1');
    await page.getByRole('button', { name: 'نعم، تم الحل' }).click();
    await expect(page.getByText('أكدت إنجاز العمل')).toBeVisible();
    expect(key).toMatch(/^[0-9a-f-]{36}$/);
    await expect(page.getByRole('radiogroup')).toBeVisible();
  });
});
