import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '@/lib/api';
import { ASSET, httpError, page, routeGet, signIn } from '@/modules/assets/test-helpers';
import { AssetDetailView } from './AssetDetailView';

vi.mock('@/lib/api', async () => (await import('@/modules/assets/test-mock')).apiMockFactory());

const WO = {
  id: 'w1', ticketNumber: 'T-100', title: 'Fix compressor', status: 'completed', priority: 'high', subType: 'hvac',
  preventive: true, planId: 'p1', createdAt: '2025-02-01T08:00:00Z', completedDate: '2025-02-02T08:00:00Z', closedAt: null,
  slaBreached: false, techReport: null, assignedToId: 'u9', assignedToName: 'Ali',
};

function routes(extra: Record<string, () => unknown> = {}) {
  routeGet({
    '/assets/a1': () => ASSET,
    '/assets/a1/work-orders': () => page([WO]),
    '/assets/a1/materials': () => [],
    '/assets/a1/downtime': () => page([]),
    ...extra,
  });
}

beforeEach(() => vi.clearAllMocks());

describe('AssetDetailView', () => {
  it('shows details, edit and decommission to writers, and the work history with a link to the order', async () => {
    signIn('maintenance_manager');
    routes();
    render(<AssetDetailView id="a1" />);
    expect(await screen.findByRole('heading', { name: 'مبرد' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'تعديل' })).toHaveAttribute('href', '/assets/a1/edit');
    expect(screen.getByRole('button', { name: 'استبعاد الأصل' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('tab', { name: 'سجل البلاغات' }));
    expect(await screen.findByRole('link', { name: 'T-100' })).toHaveAttribute('href', '/work-orders/w1');
  });

  it('hides write actions and the materials tab for a control room user', async () => {
    signIn('control_room');
    routes();
    render(<AssetDetailView id="a1" />);
    await screen.findByRole('heading', { name: 'مبرد' });
    expect(screen.queryByRole('link', { name: 'تعديل' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'استبعاد الأصل' })).not.toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: 'المواد' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('tab', { name: 'التوقفات' }));
    expect(await screen.findByRole('button', { name: 'تسجيل توقف' })).toBeInTheDocument();
  });

  it('requires a reason to decommission, then posts it', async () => {
    signIn('hospital_supervisor');
    routes();
    vi.mocked(api.post).mockResolvedValue({ data: { ...ASSET, decommissionedAt: '2025-03-01T00:00:00Z' } } as never);
    render(<AssetDetailView id="a1" />);
    await userEvent.click(await screen.findByRole('button', { name: 'استبعاد الأصل' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: 'استبعاد الأصل' }));
    expect(await within(dialog).findByText('السبب مطلوب')).toBeInTheDocument();
    expect(api.post).not.toHaveBeenCalled();
    await userEvent.type(within(dialog).getByLabelText(/السبب/), 'end of life');
    await userEvent.click(within(dialog).getByRole('button', { name: 'استبعاد الأصل' }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/assets/a1/decommission', { reason: 'end of life' }));
    expect(await screen.findByText('تم استبعاد الأصل.')).toBeInTheDocument();
  });

  it('records a downtime and shows a server conflict message', async () => {
    signIn('technician');
    routes();
    vi.mocked(api.post).mockRejectedValueOnce(httpError(409, 'Downtime already open'));
    render(<AssetDetailView id="a1" />);
    await userEvent.click(await screen.findByRole('tab', { name: 'التوقفات' }));
    await userEvent.click(await screen.findByRole('button', { name: 'تسجيل توقف' }));
    expect(await screen.findByText('السبب مطلوب')).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText(/سبب التوقف/), 'compressor');
    await userEvent.click(screen.getByRole('button', { name: 'تسجيل توقف' }));
    expect(await screen.findByText('Downtime already open')).toBeInTheDocument();
  });

  it('shows not-found for an asset outside the caller scope', async () => {
    signIn('maintenance_manager');
    routeGet({ '/assets/zzz': () => { throw httpError(404); } });
    render(<AssetDetailView id="zzz" />);
    expect(await screen.findByText('العنصر غير موجود أو خارج نطاق صلاحياتك.')).toBeInTheDocument();
  });
});
