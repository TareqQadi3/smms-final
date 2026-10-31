import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MaterialRequestsList } from './MaterialRequestsList';
import { materialsApi } from '../api';
import { httpError, page, signIn } from '@/modules/inventory/testUtils';

vi.mock('../api', () => ({ materialsApi: { list: vi.fn() } }));
const api = vi.mocked(materialsApi);

describe('MaterialRequestsList', () => {
  beforeEach(() => vi.clearAllMocks());

  it('lists requests with status badges in Arabic by default', async () => {
    signIn('warehouse', { lang: 'ar' });
    api.list.mockResolvedValue(page([{ id: 'mr-1', requestNumber: 'MR-1', status: 'approved', workOrder: { id: 'w', ticketNumber: 'WO-9', status: 'in_progress' }, createdAt: '2026-10-01T00:00:00Z' }]) as never);
    render(<MaterialRequestsList />);
    expect(await screen.findByRole('link', { name: 'MR-1' })).toHaveAttribute('href', '/material-requests/mr-1');
    expect(screen.getAllByText('معتمد').length).toBeGreaterThan(0);
    expect(screen.getByRole('heading', { name: 'طلبات المواد' })).toBeInTheDocument();
  });

  it('empty, error and 403 states', async () => {
    signIn('warehouse');
    api.list.mockResolvedValueOnce(page([]) as never);
    const a = render(<MaterialRequestsList />);
    expect(await screen.findByText('No material requests')).toBeInTheDocument();
    a.unmount();
    api.list.mockRejectedValueOnce(httpError(500));
    const b = render(<MaterialRequestsList />);
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    b.unmount();
    api.list.mockRejectedValueOnce(httpError(403));
    render(<MaterialRequestsList />);
    expect(await screen.findByText('Access denied')).toBeInTheDocument();
  });

  it('roles outside the matrix never call the API', () => {
    signIn('resident');
    render(<MaterialRequestsList />);
    expect(screen.getByRole('alert')).toHaveTextContent('Access denied');
    expect(api.list).not.toHaveBeenCalled();
  });
});
