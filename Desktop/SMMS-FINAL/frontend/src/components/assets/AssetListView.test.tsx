import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api, centerApi } from '@/lib/api';
import { ASSET, CENTER, httpError, page, routeGet, signIn } from '@/modules/assets/test-helpers';
import { AssetListView } from './AssetListView';

vi.mock('@/lib/api', async () => (await import('@/modules/assets/test-mock')).apiMockFactory());

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(centerApi.getAll).mockResolvedValue({ data: [CENTER, { ...CENTER, id: 'c2', name: 'Two', nameAr: 'الثاني' }] } as never);
});

describe('AssetListView', () => {
  it('lists assets and offers "add" only to roles that may write', async () => {
    signIn('hospital_supervisor');
    routeGet({ '/assets': () => page([ASSET, { ...ASSET, id: 'a2', name: 'Pump', nameAr: 'مضخة', decommissionedAt: '2025-01-01T00:00:00Z' }]) });
    render(<AssetListView />);
    expect(await screen.findByRole('link', { name: 'مبرد' })).toHaveAttribute('href', '/assets/a1');
    expect(screen.getByText('مستبعد')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'إضافة أصل' })).toHaveAttribute('href', '/assets/new');
  });

  it('hides "add" for read-only roles (control room)', async () => {
    signIn('control_room');
    routeGet({ '/assets': () => page([ASSET]) });
    render(<AssetListView />);
    await screen.findByRole('link', { name: 'مبرد' });
    expect(screen.queryByRole('link', { name: 'إضافة أصل' })).not.toBeInTheDocument();
  });

  it('shows PermissionDenied for a role outside the matrix, without calling the API', () => {
    signIn('resident');
    render(<AssetListView />);
    expect(screen.getByRole('alert')).toHaveTextContent('غير مصرح');
    expect(api.get).not.toHaveBeenCalledWith('/assets', expect.anything());
  });

  it('maps a server 403 to PermissionDenied and a 500 to a retryable error', async () => {
    signIn('technician');
    routeGet({ '/assets': () => { throw httpError(403); } });
    const { unmount } = render(<AssetListView />);
    expect(await screen.findByRole('alert')).toHaveTextContent('غير مصرح');
    unmount();

    routeGet({ '/assets': () => { throw httpError(500); } });
    render(<AssetListView />);
    expect(await screen.findByRole('button', { name: 'إعادة المحاولة' })).toBeInTheDocument();
  });

  it('shows the empty state and sends filters to the API', async () => {
    signIn('maintenance_manager');
    routeGet({ '/assets': () => page([]) });
    render(<AssetListView />);
    expect(await screen.findByText('لا توجد أصول')).toBeInTheDocument();
    await userEvent.type(screen.getByRole('searchbox'), 'pump');
    await userEvent.selectOptions(screen.getByLabelText('الحالة'), 'broken');
    await userEvent.click(screen.getByRole('button', { name: 'تصفية' }));
    await waitFor(() => {
      const last = vi.mocked(api.get).mock.calls.filter((c) => c[0] === '/assets').pop()!;
      expect((last[1] as { params: Record<string, unknown> }).params).toMatchObject({ q: 'pump', status: 'broken', page: 0 });
    });
  });
});
