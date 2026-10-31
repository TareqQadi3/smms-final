import { devices, expect, test } from '@playwright/test';
import { json, page0, signIn, wo } from './helpers';

// iPhone-size viewport, touch, mobile UA (Chromium engine; real Safari is not available here)
const { defaultBrowserType: _ignored, ...iphone } = devices['iPhone 13'];
void _ignored;
test.use({ ...iphone });

test('mobile viewport: queue fits without horizontal scroll, nav is reachable and RTL', async ({ page }) => {
  await signIn(page);
  await page.routeWebSocket(/ws\/notifications/, (ws) => ws.close());
  await page.route('**/api/**', async (route) => {
    const p = new URL(route.request().url()).pathname.replace(/^\/api/, '');
    if (p === '/work-orders') return json(route, 200, page0(new URL(route.request().url()).searchParams.get('status') === 'assigned' ? [wo({ isEmergency: true, title: 'عنوان طويل جدا لبلاغ صيانة في الجناح الثالث من المستشفى لاختبار التفاف النص على الشاشات الصغيرة' })] : []));
    if (p === '/notifications/unread-count') return json(route, 200, 3);
    return json(route, 200, {});
  });
  await page.goto('/tech');
  await expect(page.getByTestId('wo-card')).toBeVisible();
  const vp = page.viewportSize()!;
  expect(vp.width).toBeLessThan(500);
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);
  expect(await page.evaluate(() => document.documentElement.dir)).toBe('rtl');
  const nav = page.getByRole('navigation', { name: 'التنقل الرئيسي' });
  await expect(nav.getByRole('link', { name: /التنبيهات/ })).toBeVisible();
  const box = await nav.getByRole('link', { name: /المهام/ }).boundingBox();
  expect(box!.height).toBeGreaterThanOrEqual(44);
  await page.screenshot({ path: 'test-results/tech-mobile.png' });
});
