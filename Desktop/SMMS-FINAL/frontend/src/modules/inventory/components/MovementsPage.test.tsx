import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MovementsPage } from './MovementsPage';
import { inventoryApi } from '../api';
import { httpError, page, signIn } from '../testUtils';

vi.mock('next/navigation', () => ({ useSearchParams: () => new URLSearchParams(''), usePathname: () => '/inventory/movements', useRouter: () => ({ push: vi.fn() }) }));
vi.mock('../api', () => ({
  inventoryApi: { listWarehouses: vi.fn(), listMovements: vi.fn(), reverse: vi.fn() },
}));
const api = vi.mocked(inventoryApi);

const mv = (over: Record<string, unknown> = {}) => ({
  id: 'm-1', kind: 'issue', item: { id: 'i-1', sku: 'S1', name: 'Filter' }, warehouse: { id: 'w-1', code: 'W1', name: 'Main' },
  quantity: 2, balanceAfter: 8, createdAt: '2026-10-01T10:00:00Z', ...over,
});

describe('MovementsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.listWarehouses.mockResolvedValue(page([]) as never);
  });

  it('shows the empty state', async () => {
    signIn('warehouse');
    api.listMovements.mockResolvedValue(page([]) as never);
    render(<MovementsPage />);
    expect(await screen.findByText('No movements')).toBeInTheDocument();
  });

  it('shows an error with retry', async () => {
    signIn('warehouse');
    api.listMovements.mockRejectedValueOnce(httpError(500)).mockResolvedValueOnce(page([mv()]) as never);
    render(<MovementsPage />);
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('Filter')).toBeInTheDocument();
  });

  it('shows PermissionDenied on 403', async () => {
    signIn('technician');
    api.listMovements.mockRejectedValue(httpError(403));
    render(<MovementsPage />);
    expect(await screen.findByText('Access denied')).toBeInTheDocument();
  });

  it('hides reverse for the warehouse role', async () => {
    signIn('warehouse');
    api.listMovements.mockResolvedValue(page([mv()]) as never);
    render(<MovementsPage />);
    await screen.findByText('Filter');
    expect(screen.queryByRole('button', { name: 'Reverse' })).toBeNull();
  });

  it('manager reverses with a mandatory reason and the same key on retry', async () => {
    signIn('maintenance_manager');
    api.listMovements.mockResolvedValue(page([mv(), mv({ id: 'm-2', reversedById: 'x' }), mv({ id: 'm-3', kind: 'reversal_in', reversalOfId: 'm-1' })]) as never);
    api.reverse.mockRejectedValueOnce(httpError(409, 'Insufficient stock to reverse')).mockResolvedValueOnce({ id: 'r' } as never);
    render(<MovementsPage />);
    const buttons = await screen.findAllByRole('button', { name: 'Reverse' });
    expect(buttons).toHaveLength(1); // reversed and reversal rows are not reversible
    await userEvent.click(buttons[0]);
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(screen.getAllByRole('button', { name: 'Reverse' }).at(-1)!);
    expect(await screen.findByText('Write a reason of at least 5 characters')).toBeInTheDocument();
    expect(api.reverse).not.toHaveBeenCalled();
    await userEvent.type(dialog.querySelector('textarea')!, 'posted twice by mistake');
    await userEvent.click(screen.getAllByRole('button', { name: 'Reverse' }).at(-1)!);
    expect(await screen.findByText(/Insufficient stock to reverse/)).toBeInTheDocument();
    await userEvent.click(screen.getAllByRole('button', { name: 'Reverse' }).at(-1)!);
    await waitFor(() => expect(api.reverse).toHaveBeenCalledTimes(2));
    expect(api.reverse.mock.calls[1][2]).toBe(api.reverse.mock.calls[0][2]);
    expect(api.reverse.mock.calls[0][1]).toBe('posted twice by mistake');
    expect(await screen.findByText('Movement reversed.')).toBeInTheDocument();
  });
});
