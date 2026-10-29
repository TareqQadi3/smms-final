import { expect, test, type Page, type Route } from '@playwright/test';

// All /api calls are mocked with page.route; no backend is needed.
type Role = string;
const WO_ID = '11111111-1111-1111-1111-111111111111';

const user = (role: Role) => ({ id: 'u-1', username: 'x', fullName: 'Test User', role, active: true });

async function signIn(page: Page, role: Role) {
  await page.addInitScript(([u]) => {
    localStorage.setItem('smms_token', 'test-token');
    localStorage.setItem('smms_user', u as string);
    localStorage.setItem('smms_lang', 'en');
  }, [JSON.stringify(user(role))]);
}

const json = (route: Route, body: unknown, status = 200) =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

interface WoState { paused: boolean; isEmergency: boolean; level: number; noteConflict: boolean; seenAuth: string[]; posts: string[] }

function baseWo(s: WoState) {
  return {
    id: WO_ID, ticketNumber: 'WO-2026-0001', title: 'Broken AC', status: 'in_progress', priority: 'high', subType: 'hvac',
    createdAt: '2026-01-01T08:00:00Z', slaDeadline: '2026-01-02T08:00:00Z', slaBreached: false,
    requestedBy: { id: 'req-1', fullName: 'Requester' }, assignedTo: { id: 'u-1', fullName: 'Tech One' },
    center: { id: 'c1', name: 'Center A' }, controlRoom: { id: 'cr1', name: 'Room 1' },
    isEmergency: s.isEmergency, emergencyDeclaredAt: s.isEmergency ? '2026-01-01T09:00:00Z' : null,
    slaPaused: s.paused, slaPausedSeconds: 3600, escalationLevel: s.level,
    asset: { id: 'as1', assetCode: 'AST-001', name: 'Chiller 1' }, assetId: 'as1',
  };
}

async function mockWorkOrder(page: Page, s: WoState) {
  await page.route('**/api/**', async (route) => {
    const req = route.request();
    const url = new URL(req.url());
    const p = url.pathname.replace(/^\/api/, '');
    s.seenAuth.push(`${req.method()} ${p} ${req.headers()['authorization'] ?? ''}`);
    if (req.method() === 'POST') s.posts.push(p);
    if (p === `/work-orders/${WO_ID}` && req.method() === 'GET') return json(route, baseWo(s));
    if (p === `/work-orders/${WO_ID}/history`) return json(route, [
      { id: 'h1', toStatus: 'in_progress', fromStatus: 'assigned', stage: 'in_progress', actorRole: 'technician', actor: { fullName: 'Tech One' }, createdAt: '2026-01-01T09:00:00Z' },
      { id: 'h2', toStatus: 'in_progress', fromStatus: 'in_progress', stage: 'note', note: 'Checked compressor', actorRole: 'technician', actor: { fullName: 'Tech One' }, createdAt: '2026-01-01T10:00:00Z' },
    ]);
    if (p === `/work-orders/${WO_ID}/info-requests`) return json(route, [
      { id: 'i1', question: 'Which floor?', requestedBy: { id: 'cr', fullName: 'Control Room' }, requestedAt: '2026-01-01T08:30:00Z', answer: 'Floor 3', answeredBy: { id: 'req-1', fullName: 'Requester' }, answeredAt: '2026-01-01T08:50:00Z', pausedSeconds: 1200 },
    ]);
    if (p === `/work-orders/${WO_ID}/escalations`) return json(route, s.level > 0 ? [
      { id: 'e1', level: 1, trigger: 'sla_breach', createdAt: '2026-01-01T12:00:00Z', notifiedCount: 2 },
      ...(s.level > 1 ? [{ id: 'e2', level: 2, trigger: 'manual', triggeredBy: { id: 'u-1', fullName: 'Test User' }, reason: 'Needs a manager', createdAt: '2026-01-01T13:00:00Z' }] : []),
    ] : []);
    if (p === `/work-orders/${WO_ID}/escalate` && req.method() === 'POST') { s.level = Math.max(2, s.level + 1); return json(route, { id: 'e2', level: s.level, trigger: 'manual' }); }
    if (p === `/work-orders/${WO_ID}/emergency` && req.method() === 'POST') { s.isEmergency = true; return json(route, baseWo(s)); }
    if (p === `/work-orders/${WO_ID}/emergency/clear` && req.method() === 'POST') { s.isEmergency = false; return json(route, baseWo(s)); }
    if (p === `/work-orders/${WO_ID}/notes` && req.method() === 'POST') {
      if (s.noteConflict) return json(route, { status: 409, message: 'modified concurrently, reload' }, 409);
      return json(route, { id: 'h3' }, 201);
    }
    if (p === `/work-orders/${WO_ID}/attachments`) return json(route, [
      { id: 'a1', fileName: 'report.pdf', mimeType: 'application/pdf', createdAt: '2026-01-01T09:00:00Z', photoPhase: null },
      { id: 'a2', fileName: 'before.png', mimeType: 'image/png', createdAt: '2026-01-01T09:00:00Z', photoPhase: 'before' },
    ]);
    if (p.startsWith(`/work-orders/${WO_ID}/attachments/`) && p.endsWith('/content')) {
      return route.fulfill({ status: 200, contentType: 'application/pdf', body: '%PDF-1.4 test' });
    }
    return json(route, {});
  });
}

const fresh = (): WoState => ({ paused: true, isEmergency: false, level: 1, noteConflict: false, seenAuth: [], posts: [] });

test.describe('work order detail (control room)', () => {
  test('shows info thread, SLA pause, asset link, escalation history and history notes', async ({ page }) => {
    await signIn(page, 'control_room');
    await mockWorkOrder(page, fresh());
    await page.goto(`/work-orders/${WO_ID}`);
    await expect(page.getByRole('heading', { level: 1, name: 'Broken AC' })).toBeVisible();
    await expect(page.getByText('Which floor?')).toBeVisible();
    await expect(page.getByText('Floor 3')).toBeVisible();
    await expect(page.getByText('SLA clock paused').first()).toBeVisible();
    await expect(page.getByRole('link', { name: /Open asset record/ })).toHaveAttribute('href', '/assets/as1');
    await expect(page.getByText('SLA breached')).toBeVisible();
    await expect(page.getByText('Checked compressor')).toBeVisible();
  });

  test('declares an emergency through a confirm dialog with a reason', async ({ page }) => {
    const s = fresh();
    await signIn(page, 'control_room');
    await mockWorkOrder(page, s);
    await page.goto(`/work-orders/${WO_ID}`);
    await page.getByRole('button', { name: 'Declare emergency' }).click();
    const dialog = page.getByRole('dialog', { name: 'Declare an emergency' });
    await dialog.getByRole('button', { name: 'Declare emergency' }).click();
    await expect(dialog.getByText('Enter a reason of at least 5 characters')).toBeVisible();
    await dialog.getByRole('textbox').fill('Water leak near the server room');
    await dialog.getByRole('button', { name: 'Declare emergency' }).click();
    await expect(page.getByText('Emergency declared')).toBeVisible();
    await expect(page.getByRole('status').filter({ hasText: 'Emergency' }).first()).toBeVisible();
    expect(s.posts).toContain(`/work-orders/${WO_ID}/emergency`);
    // control room may not clear
    await expect(page.getByRole('button', { name: 'Clear emergency' })).toHaveCount(0);
  });

  test('manual escalation adds a level-2 history row', async ({ page }) => {
    const s = fresh();
    s.paused = false; // paused tickets cannot be escalated
    await signIn(page, 'maintenance_manager');
    await mockWorkOrder(page, s);
    await page.goto(`/work-orders/${WO_ID}`);
    await page.getByRole('button', { name: 'Escalate manually' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('textbox').fill('Needs a manager');
    await dialog.getByRole('button', { name: 'Escalate manually' }).click();
    await expect(page.getByText('Needs a manager')).toBeVisible();
    await expect(page.getByText('Manual', { exact: true })).toBeVisible();
  });

  test('downloads an attachment through the API with the Bearer header', async ({ page }) => {
    const s = fresh();
    await signIn(page, 'control_room');
    await mockWorkOrder(page, s);
    await page.goto(`/work-orders/${WO_ID}`);
    const dl = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Download: report.pdf' }).click();
    expect((await dl).suggestedFilename()).toBe('report.pdf');
    expect(s.seenAuth.some((l) => l.includes('/attachments/a1/content Bearer test-token'))).toBe(true);
    await expect(page.getByRole('region', { name: 'Before' })).toBeVisible();
  });

  test('a 409 on save shows the reload banner and reload refetches', async ({ page }) => {
    const s = fresh();
    s.noteConflict = true;
    await signIn(page, 'control_room');
    await mockWorkOrder(page, s);
    await page.goto(`/work-orders/${WO_ID}`);
    await page.getByLabel('Note text').fill('Another note');
    await page.getByRole('button', { name: 'Save note' }).click();
    const banner = page.getByRole('alert').filter({ hasText: 'Edit conflict' });
    await expect(banner).toBeVisible();
    s.noteConflict = false;
    const before = s.seenAuth.length;
    await banner.getByRole('button', { name: 'Reload' }).click();
    await expect(banner).toHaveCount(0);
    expect(s.seenAuth.length).toBeGreaterThan(before);
  });

  test('technician sees no emergency or escalation controls', async ({ page }) => {
    await signIn(page, 'technician');
    await mockWorkOrder(page, fresh());
    await page.goto(`/work-orders/${WO_ID}`);
    await expect(page.getByRole('heading', { level: 1, name: 'Broken AC' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Declare emergency' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Escalate manually' })).toHaveCount(0);
    await expect(page.getByRole('heading', { name: 'Escalation' })).toHaveCount(0);
    await expect(page.getByLabel('Note text')).toBeVisible(); // assigned technician may add notes
  });

  test('Arabic RTL render', async ({ page }) => {
    await signIn(page, 'control_room');
    await page.addInitScript(() => localStorage.setItem('smms_lang', 'ar'));
    await mockWorkOrder(page, fresh());
    await page.goto(`/work-orders/${WO_ID}`);
    await expect(page.getByText('طلبات المعلومات')).toBeVisible();
    await expect(page.locator('div[dir="rtl"]').first()).toBeVisible();
  });
});

test.describe('approvals', () => {
  const list = [{ id: WO_ID, ticketNumber: 'WO-9', title: 'Pump', status: 'waiting_approval', priority: 'high', subType: 'x', createdAt: '2026-01-01T00:00:00Z', warehouseNotes: 'Needs 2 seals', assignedTo: { id: 't', fullName: 'Tech' } }];
  const mock = (page: Page, decisions: string[]) =>
    page.route('**/api/**', async (route) => {
      const u = new URL(route.request().url());
      if (u.pathname.endsWith('/approval')) { decisions.push(u.searchParams.get('decision') ?? ''); return json(route, {}); }
      if (u.pathname.endsWith('/work-orders')) return json(route, { content: list, totalPages: 1 });
      return json(route, {});
    });

  test('read-only for non-OCM roles', async ({ page }) => {
    await signIn(page, 'maintenance_manager');
    await mock(page, []);
    await page.goto('/approvals');
    await expect(page.getByText('Pump')).toBeVisible();
    await expect(page.getByText('Needs 2 seals')).toBeVisible();
    await expect(page.getByText(/Read only: approval decisions/)).toBeVisible();
    await expect(page.getByRole('button', { name: /Approve/ })).toHaveCount(0);
  });

  test('operating company manager approves; reject requires a note', async ({ page }) => {
    const decisions: string[] = [];
    await signIn(page, 'operating_company_manager');
    await mock(page, decisions);
    await page.goto('/approvals');
    await page.getByRole('button', { name: /Reject/ }).first().click();
    await page.getByRole('dialog').getByRole('button', { name: 'Confirm' }).click();
    expect(decisions).toHaveLength(0);
    await page.getByRole('dialog').getByRole('textbox').fill('too expensive');
    await page.getByRole('dialog').getByRole('button', { name: 'Confirm' }).click();
    await expect(page.getByText('Decision recorded')).toBeVisible();
    expect(decisions).toEqual(['rejected']);
  });
});

test.describe('notification screens', () => {
  test('preferences are honest about an unconfigured provider', async ({ page }) => {
    await signIn(page, 'resident');
    await page.route('**/api/notifications/preferences', (route) =>
      route.request().method() === 'PUT'
        ? json(route, { emailEnabled: true, smsEnabled: false, emailAvailable: false, smsAvailable: false })
        : json(route, { emailEnabled: false, smsEnabled: false, types: null, emailAvailable: false, smsAvailable: false }));
    await page.goto('/settings/notifications');
    await expect(page.getByText(/email provider is not configured/i)).toBeVisible();
    await page.getByLabel('Receive notifications by Email').check();
    await page.getByRole('button', { name: 'Save settings' }).click();
    await expect(page.getByText('Settings saved')).toBeVisible();
  });

  test('delivery log is admin only', async ({ page }) => {
    await signIn(page, 'maintenance_manager');
    let called = false;
    await page.route('**/api/admin/notification-**', (route) => { called = true; return json(route, {}); });
    await page.goto('/admin/notification-deliveries');
    await expect(page.getByText('This page is for system administrators only')).toBeVisible();
    expect(called).toBe(false);
  });

  test('admin sees providers and filtered rows with pagination', async ({ page }) => {
    await signIn(page, 'system_admin');
    const queries: string[] = [];
    await page.route('**/api/admin/notification-providers', (route) =>
      json(route, [{ channel: 'email', provider: 'smtp', enabled: false, configured: false, problems: [] }]));
    await page.route('**/api/admin/notification-deliveries**', (route) => {
      queries.push(new URL(route.request().url()).search);
      return json(route, { content: [{ id: 'd1', channel: 'email', destinationMasked: 'a***@x.com', status: 'not_configured', attempts: 0, provider: 'smtp', createdAt: '2026-01-01T10:00:00Z' }], page: 0, size: 20, totalElements: 40, totalPages: 2 });
    });
    await page.goto('/admin/notification-deliveries');
    await expect(page.getByText('a***@x.com')).toBeVisible();
    await page.getByLabel('Status').selectOption('dead');
    await page.getByRole('button', { name: 'Apply' }).click();
    await expect.poll(() => queries.some((q) => q.includes('status=dead'))).toBe(true);
  });
});

test.describe('new request', () => {
  const mock = (page: Page, created: unknown[]) =>
    page.route('**/api/**', async (route) => {
      const req = route.request();
      const u = new URL(req.url());
      const p = u.pathname.replace(/^\/api/, '');
      if (p === '/control-rooms' || p === '/admin/control-rooms') return json(route, [{ id: 'cr1', name: 'Room 1', center: { id: 'c1', name: 'C' } }]);
      if (p.startsWith('/maintenance-types')) return json(route, [{ id: 'm1', name: 'HVAC', nameAr: 'تكييف', controlRoomType: 'x' }]);
      if (p === '/assets') return json(route, { content: [{ id: 'as1', assetCode: 'AST-1', name: 'Chiller' }] });
      if (p === '/work-orders' && req.method() === 'POST') { created.push(req.postDataJSON()); return json(route, { id: WO_ID }, 201); }
      if (p === `/work-orders/${WO_ID}/emergency`) { created.push({ emergency: req.postDataJSON() }); return json(route, {}); }
      if (p === '/work-orders') return json(route, { content: [] });
      return json(route, {});
    });

  test('control room can pick an asset and declare an emergency', async ({ page }) => {
    const created: unknown[] = [];
    await signIn(page, 'control_room');
    await mock(page, created);
    await page.goto('/new-request');
    await page.locator('select').first().selectOption('cr1');
    await expect(page.getByLabel('Asset (optional)')).toBeVisible();
    await page.getByLabel('Asset (optional)').selectOption('as1');
    await page.locator('select', { hasText: 'HVAC' }).selectOption({ label: 'HVAC' });
    await page.getByPlaceholder('Enter request title').fill('Chiller down');
    await page.getByLabel('Emergency request').check();
    await page.getByLabel(/Emergency reason/).fill('Server room overheating');
    await page.getByRole('button', { name: /Submit Request/ }).click();
    await expect(page.getByText('Request submitted successfully!')).toBeVisible();
    expect(JSON.stringify(created)).toContain('"assetId":"as1"');
    expect(JSON.stringify(created)).toContain('Server room overheating');
  });

  test('resident sees neither asset nor emergency fields', async ({ page }) => {
    await signIn(page, 'resident');
    await mock(page, []);
    await page.goto('/new-request');
    await expect(page.getByRole('heading', { name: 'New Maintenance Request' })).toBeVisible();
    await expect(page.getByLabel('Emergency request')).toHaveCount(0);
    await expect(page.getByLabel('Asset (optional)')).toHaveCount(0);
  });
});
