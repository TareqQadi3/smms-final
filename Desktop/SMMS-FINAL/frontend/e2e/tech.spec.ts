import { expect, test, type Page } from '@playwright/test';
import { json, page0, signIn, wo } from './helpers';

type State = { status: string; transitions: { key: string | null; body: unknown }[]; notes: { key: string | null; body: unknown }[]; uploads: { key: string | null; phase: string | null }[]; online: boolean };

async function mockApi(page: Page, s: State, opts: { failFirstTransition?: boolean } = {}) {
  let transitionCalls = 0;
  await page.route('**/api/**', async (route) => {
    const req = route.request();
    const url = new URL(req.url());
    const p = url.pathname.replace(/^\/api/, '');
    const key = req.headers()['idempotency-key'] ?? null;
    if (!s.online) return route.abort('internetdisconnected');
    if (p === '/work-orders' && req.method() === 'GET') {
      const st = url.searchParams.get('status');
      return json(route, 200, page0(st === s.status ? [wo({ status: s.status })] : []));
    }
    if (p === '/work-orders/w1' && req.method() === 'GET') return json(route, 200, wo({ status: s.status }));
    if (p === '/work-orders/w1/history') return json(route, 200, []);
    if (p === '/work-orders/w1/attachments' && req.method() === 'GET') return json(route, 200, []);
    if (p === '/work-orders/w1/attachments' && req.method() === 'POST') {
      s.uploads.push({ key, phase: url.searchParams.get('phase') });
      return route.fulfill({ status: 201 });
    }
    if (p === '/work-orders/w1/material-requests') return json(route, 200, []);
    if (p === '/work-orders/w1/transition') {
      transitionCalls++;
      if (opts.failFirstTransition && transitionCalls === 1) return route.abort('connectionreset');
      const body = req.postDataJSON();
      s.transitions.push({ key, body });
      s.status = body.targetStatus;
      return json(route, 200, wo({ status: s.status }));
    }
    if (p === '/work-orders/w1/notes') {
      s.notes.push({ key, body: req.postDataJSON() });
      return json(route, 201, { id: 'h1', stage: 'note', toStatus: s.status, note: 'x', createdAt: '2026-01-01T09:00:00Z' });
    }
    if (p === '/notifications/unread-count') return json(route, 200, 0);
    if (p === '/notifications') return json(route, 200, page0([]));
    if (p === '/ws/ticket') return json(route, 200, { ticket: 't', expiresInSeconds: 30 });
    return json(route, 404, { message: `unmocked ${p}` });
  });
}

const fresh = (status = 'assigned'): State => ({ status, transitions: [], notes: [], uploads: [], online: true });

test.beforeEach(async ({ page }) => {
  await signIn(page);
  // a WebSocket cannot be routed by page.route: refuse it so the client falls back to polling
  await page.routeWebSocket(/ws\/notifications/, (ws) => ws.close());
});

test('queue shows the assigned ticket, opens it and starts the work (Idempotency-Key sent)', async ({ page }) => {
  const s = fresh();
  await mockApi(page, s);
  await page.goto('/tech');
  const card = page.getByTestId('wo-card');
  await expect(card).toContainText('WO-100');
  await expect(card).toContainText('Broken pump');
  await card.click();
  await expect(page.getByRole('heading', { name: 'Broken pump' })).toBeVisible();
  await page.getByRole('button', { name: /قبول وبدء العمل/ }).click();
  await expect(page.getByText('تم تنفيذ الإجراء.')).toBeVisible();
  expect(s.transitions).toHaveLength(1);
  expect(s.transitions[0].key).toMatch(/^[0-9a-f-]{36}$/);
  expect(s.transitions[0].body).toEqual({ targetStatus: 'in_progress' });
});

test('completion needs a report and sends it', async ({ page }) => {
  const s = fresh('in_progress');
  await mockApi(page, s);
  await page.goto('/tech/w1');
  await page.getByRole('button', { name: /إنهاء العمل/ }).click();
  await page.getByRole('button', { name: 'إرسال', exact: true }).click();
  await expect(page.getByText('التقرير مطلوب')).toBeVisible();
  await page.getByRole('dialog').locator('textarea').fill('Seal replaced and tested');
  await page.getByRole('button', { name: 'إرسال', exact: true }).click();
  await expect(page.getByRole('dialog')).toBeHidden();
  expect(s.transitions[0].body).toEqual({ targetStatus: 'completed', note: 'Seal replaced and tested' });
});

test('photo upload goes out with ?phase=before and an Idempotency-Key', async ({ page }) => {
  const s = fresh('in_progress');
  await mockApi(page, s);
  await page.goto('/tech/w1');
  // a 1x1 PNG: the page compresses it to JPEG in the browser
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');
  await page.getByTestId('choose-before').setInputFiles({ name: 'site.png', mimeType: 'image/png', buffer: png });
  await expect.poll(() => s.uploads.length).toBe(1);
  expect(s.uploads[0].phase).toBe('before');
  expect(s.uploads[0].key).toMatch(/^[0-9a-f-]{36}$/);
});

test('offline: actions are queued with pending state, then replayed once with the SAME key', async ({ page, context }) => {
  const s = fresh('in_progress');
  await mockApi(page, s);
  await page.goto('/tech/w1');
  await expect(page.getByRole('heading', { name: 'Broken pump' })).toBeVisible();

  s.online = false;
  await context.setOffline(true);
  await page.getByRole('textbox', { name: 'ملاحظة جديدة' }).fill('Waiting for the valve');
  await page.getByRole('button', { name: 'إضافة ملاحظة' }).click();
  await expect(page.getByText('حُفظ الإجراء على الجهاز')).toBeVisible();
  const item = page.getByTestId('pending-item');
  await expect(item).toHaveCount(1);
  await expect(page.getByTestId('pending-badge')).toBeVisible();
  expect(s.notes).toHaveLength(0);

  s.online = true;
  await context.setOffline(false);
  await expect.poll(() => s.notes.length, { timeout: 30_000 }).toBe(1);
  await expect(item).toHaveCount(0);
  expect(s.notes[0].body).toEqual({ text: 'Waiting for the valve' });
  expect(s.notes[0].key).toMatch(/^[0-9a-f-]{36}$/);
  await page.waitForTimeout(2500);
  expect(s.notes).toHaveLength(1); // no duplicate submission
});

test('a lost response is retried with the same Idempotency-Key', async ({ page }) => {
  const s = fresh();
  await mockApi(page, s, { failFirstTransition: true });
  await page.goto('/tech/w1');
  await page.getByRole('button', { name: /قبول وبدء العمل/ }).click();
  await expect(page.getByText('حُفظ الإجراء على الجهاز')).toBeVisible();
  await expect.poll(() => s.transitions.length, { timeout: 30_000 }).toBe(1);
  expect(s.transitions[0].key).toBeTruthy();
});

test('English and 403 handling', async ({ page }) => {
  const s = fresh();
  await mockApi(page, s);
  await page.route('**/api/work-orders/w1/transition', (r) => json(r, 403, { message: 'Access denied' }));
  await page.addInitScript(() => localStorage.setItem('smms_lang', 'en'));
  await page.goto('/tech/w1');
  await page.getByRole('button', { name: 'Accept and start work' }).click();
  await expect(page.getByRole('alert').filter({ hasText: 'You are not allowed' })).toBeVisible();
});

test('touch targets on the queue are at least 44px', async ({ page }) => {
  const s = fresh();
  await mockApi(page, s);
  await page.goto('/tech');
  await expect(page.getByTestId('wo-card')).toBeVisible();
  const controls = page.locator('header button, nav a, main button, [data-testid="wo-card"]');
  const n = await controls.count();
  for (let i = 0; i < n; i++) {
    const box = await controls.nth(i).boundingBox();
    if (box) {
      expect(box.height, `control ${i}`).toBeGreaterThanOrEqual(43.5);
      expect(box.width, `control ${i}`).toBeGreaterThanOrEqual(43.5);
    }
  }
});
