import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MaterialRequestDetail } from './MaterialRequestDetail';
import { materialsApi } from '../api';
import { httpError, signIn } from '@/modules/inventory/testUtils';
import type { MaterialRequest } from '../types';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push }), usePathname: () => '/' }));
vi.mock('@/modules/procurement/api', () => ({ procurementApi: { listSuppliers: vi.fn().mockResolvedValue({ content: [] }) } }));
vi.mock('../api', () => ({
  materialsApi: {
    get: vi.fn(), approve: vi.fn(), reject: vi.fn(), reserve: vi.fn(), issue: vi.fn(), consume: vi.fn(),
    giveBack: vi.fn(), close: vi.fn(), cancel: vi.fn(), createPurchaseOrder: vi.fn(),
  },
}));
const api = vi.mocked(materialsApi);

const mr = (o: Partial<MaterialRequest> = {}): MaterialRequest => ({
  id: 'mr-1', requestNumber: 'MR-0001', status: 'submitted', createdAt: '2026-10-01T10:00:00Z', version: 7,
  workOrder: { id: 'wo-1', ticketNumber: 'WO-1', status: 'in_progress' }, requestedBy: { id: 'tech', fullName: 'Tech' },
  warehouse: { id: 'w', code: 'W1', name: 'Main' },
  lines: [{ id: 'l-1', item: { id: 'i-1', sku: 'SKU-1', name: 'Filter' }, qtyRequested: 5, qtyApproved: 5, qtyReserved: 0, qtyIssued: 0, qtyConsumed: 0, qtyReturned: 0 }],
  ...o,
});
const buttons = () => screen.queryAllByRole('button').map((b) => b.textContent);

describe('MaterialRequestDetail role and state gating', () => {
  beforeEach(() => vi.clearAllMocks());

  it('submitted: manager can approve/reject/cancel; warehouse cannot approve', async () => {
    api.get.mockResolvedValue(mr());
    signIn('maintenance_manager');
    const { unmount } = render(<MaterialRequestDetail id="mr-1" />);
    await screen.findByText(/MR-0001/);
    expect(buttons()).toEqual(expect.arrayContaining(['Approve', 'Reject', 'Cancel request']));
    unmount();
    signIn('warehouse');
    render(<MaterialRequestDetail id="mr-1" />);
    await screen.findByText(/MR-0001/);
    expect(buttons()).not.toContain('Approve');
    expect(buttons()).toContain('Cancel request');
  });

  it('requester technician may cancel only own submitted request', async () => {
    api.get.mockResolvedValue(mr());
    signIn('technician', { id: 'someone-else' });
    const { unmount } = render(<MaterialRequestDetail id="mr-1" />);
    await screen.findByText(/MR-0001/);
    expect(buttons()).not.toContain('Cancel request');
    unmount();
    signIn('technician', { id: 'tech' });
    render(<MaterialRequestDetail id="mr-1" />);
    await screen.findByText(/MR-0001/);
    expect(buttons()).toContain('Cancel request');
  });

  it('approved: warehouse reserves/issues, manager creates a PO; technician has no actions', async () => {
    api.get.mockResolvedValue(mr({ status: 'approved' }));
    signIn('warehouse');
    const { unmount } = render(<MaterialRequestDetail id="mr-1" />);
    await screen.findByText(/MR-0001/);
    expect(buttons()).toEqual(expect.arrayContaining(['Reserve', 'Issue', 'Create purchase order']));
    unmount();
    signIn('technician', { id: 'x' });
    render(<MaterialRequestDetail id="mr-1" />);
    await screen.findByText(/MR-0001/);
    expect(screen.getByRole('group', { name: 'Actions' }).children.length).toBe(0);
  });

  it('issued with materials in hand: technician consumes, warehouse returns and closes', async () => {
    api.get.mockResolvedValue(mr({ status: 'issued', lines: [{ ...mr().lines[0], qtyIssued: 5, qtyConsumed: 1 }] }));
    signIn('technician', { id: 'tech' });
    const { unmount } = render(<MaterialRequestDetail id="mr-1" />);
    await screen.findByText(/MR-0001/);
    expect(buttons()).toContain('Record consumption');
    expect(buttons()).not.toContain('Return to warehouse');
    unmount();
    signIn('warehouse');
    render(<MaterialRequestDetail id="mr-1" />);
    await screen.findByText(/MR-0001/);
    expect(buttons()).toEqual(expect.arrayContaining(['Return to warehouse', 'Close request']));
    expect(buttons()).not.toContain('Record consumption');
  });

  it('terminal statuses show no actions', async () => {
    api.get.mockResolvedValue(mr({ status: 'closed' }));
    signIn('maintenance_manager');
    render(<MaterialRequestDetail id="mr-1" />);
    await screen.findByText(/MR-0001/);
    expect(screen.getByRole('group', { name: 'Actions' }).children.length).toBe(0);
  });
});

describe('MaterialRequestDetail actions', () => {
  beforeEach(() => vi.clearAllMocks());

  it('reserve: 409 shows the message and the retry reuses the same Idempotency-Key', async () => {
    api.get.mockResolvedValue(mr({ status: 'approved' }));
    api.reserve.mockRejectedValueOnce(httpError(409, 'Insufficient stock for SKU-1: available 2')).mockResolvedValueOnce(mr({ status: 'reserved' }));
    signIn('warehouse');
    render(<MaterialRequestDetail id="mr-1" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Reserve' }));
    await userEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Reserve' }));
    expect(await screen.findByText(/Insufficient stock for SKU-1: available 2/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Reserve' }));
    await userEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Reserve' }));
    await waitFor(() => expect(api.reserve).toHaveBeenCalledTimes(2));
    expect(api.reserve.mock.calls[0][0]).toBe('mr-1');
    expect(api.reserve.mock.calls[0][1]).toBe(7);
    expect(api.reserve.mock.calls[1][2]).toBe(api.reserve.mock.calls[0][2]);
    expect(await screen.findByText('Materials reserved.')).toBeInTheDocument();
  });

  it('issue: prefilled outstanding quantity, bounded client-side, sends a key', async () => {
    api.get.mockResolvedValue(mr({ status: 'reserved' }));
    api.issue.mockResolvedValue(mr({ status: 'issued' }));
    signIn('warehouse');
    render(<MaterialRequestDetail id="mr-1" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Issue' }));
    const dialog = await screen.findByRole('dialog');
    const qty = within(dialog).getByRole('textbox', { name: /SKU-1/ });
    expect(qty).toHaveValue('5');
    await userEvent.clear(qty);
    await userEvent.type(qty, '6');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Issue' }));
    expect(await within(dialog).findByText(/exceeds the allowed maximum \(5\)/)).toBeInTheDocument();
    expect(api.issue).not.toHaveBeenCalled();
    await userEvent.clear(qty);
    await userEvent.type(qty, '3');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Issue' }));
    await waitFor(() => expect(api.issue).toHaveBeenCalled());
    expect(api.issue.mock.calls[0][1]).toEqual([{ lineId: 'l-1', quantity: 3 }]);
    expect(api.issue.mock.calls[0][3]).toMatch(/^[A-Za-z0-9-]{8,64}$/);
  });

  it('return requires a reason', async () => {
    api.get.mockResolvedValue(mr({ status: 'issued', lines: [{ ...mr().lines[0], qtyIssued: 5 }] }));
    api.giveBack.mockResolvedValue(mr({ status: 'issued' }));
    signIn('warehouse');
    render(<MaterialRequestDetail id="mr-1" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Return to warehouse' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.type(within(dialog).getByRole('textbox', { name: /SKU-1/ }), '2');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Return to warehouse' }));
    expect(await within(dialog).findByText('This field is required')).toBeInTheDocument();
    expect(api.giveBack).not.toHaveBeenCalled();
    await userEvent.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'not needed anymore');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Return to warehouse' }));
    await waitFor(() => expect(api.giveBack).toHaveBeenCalled());
    expect(api.giveBack.mock.calls[0][2]).toBe('not needed anymore');
  });

  it('approve sends per-line approved quantities and the version', async () => {
    api.get.mockResolvedValue(mr());
    api.approve.mockResolvedValue(mr({ status: 'approved' }));
    signIn('maintenance_manager');
    render(<MaterialRequestDetail id="mr-1" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Approve' }));
    const dialog = await screen.findByRole('dialog');
    const qty = within(dialog).getByRole('textbox', { name: /SKU-1/ });
    await userEvent.clear(qty);
    await userEvent.type(qty, '4');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Approve' }));
    await waitFor(() => expect(api.approve).toHaveBeenCalledWith('mr-1', { note: undefined, lines: [{ lineId: 'l-1', approvedQuantity: 4 }], version: 7 }));
  });

  it('403 shows PermissionDenied', async () => {
    api.get.mockRejectedValue(httpError(403));
    signIn('technician');
    render(<MaterialRequestDetail id="mr-1" />);
    expect(await screen.findByText('Access denied')).toBeInTheDocument();
  });
});
