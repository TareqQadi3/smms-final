import type { Page, Route } from '@playwright/test';

export const TECH = { token: 'acc-1', refreshToken: 'ref-1', role: 'technician', userId: 'u-tech', username: 'tech1', fullName: 'Tech One', fullNameAr: 'فني', passwordChangeRequired: false };

export function json(route: Route, status: number, body: unknown, headers: Record<string, string> = {}) {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body), headers });
}

export function wo(over: Record<string, unknown> = {}) {
  return {
    id: 'w1', ticketNumber: 'WO-100', status: 'assigned', currentStage: 'assigned', priority: 'high', title: 'Broken pump',
    description: 'Pump in room 4 leaks', locationDetail: 'Ward 4', createdAt: '2026-01-01T08:00:00Z', slaDeadline: '2030-01-01T08:00:00Z',
    center: { id: 'c1', name: 'Center', nameAr: 'مركز' }, asset: null, ...over,
  };
}

export const page0 = (content: unknown[]) => ({ content, totalElements: content.length, totalPages: 1, number: 0, size: 50 });

/** Seeds a signed-in technician before any script runs. */
export async function signIn(page: Page, user = TECH) {
  await page.addInitScript((u) => {
    // seed once per tab so a later logout or expired session is not undone by the next navigation
    if (sessionStorage.getItem('e2e-seeded')) return;
    sessionStorage.setItem('e2e-seeded', '1');
    localStorage.setItem('smms_token', u.token);
    localStorage.setItem('smms_refresh_token', u.refreshToken);
    localStorage.setItem('smms_user', JSON.stringify({ id: u.userId, username: u.username, fullName: u.fullName, role: u.role, active: true }));
  }, user);
}
