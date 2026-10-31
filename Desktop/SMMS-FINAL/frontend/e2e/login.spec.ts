import { expect, test } from '@playwright/test';

// Every /api call is mocked; the smoke test never needs a backend.
test.beforeEach(async ({ page }) => {
  await page.route('**/api/**', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: '{}' }));
});

test('login page is Arabic RTL by default and toggles to English LTR', async ({ page }) => {
  await page.goto('/login');

  const form = page.locator('div[dir]').first();
  await expect(form).toHaveAttribute('dir', 'rtl');
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('نظام إدارة الصيانة');
  await expect(page.getByLabel('اسم المستخدم')).toBeVisible();
  await expect(page.getByRole('button', { name: 'تسجيل الدخول' })).toBeVisible();

  await page.getByRole('button', { name: 'English' }).click();

  await expect(form).toHaveAttribute('dir', 'ltr');
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Maintenance Management');
  await expect(page.getByLabel('Username')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Sign In' })).toBeVisible();
});

test('failed login shows a localized alert from the mocked API', async ({ page }) => {
  await page.route('**/api/auth/login', (route) =>
    route.fulfill({ status: 401, contentType: 'application/json', body: JSON.stringify({ message: 'bad' }) })
  );
  await page.goto('/login');
  await page.getByLabel('اسم المستخدم').fill('x');
  await page.getByLabel('كلمة المرور', { exact: true }).fill('y');
  await page.getByRole('button', { name: 'تسجيل الدخول' }).click();
  await expect(page.getByRole('alert').filter({ hasText: 'فشل تسجيل الدخول' })).toBeVisible();
});
