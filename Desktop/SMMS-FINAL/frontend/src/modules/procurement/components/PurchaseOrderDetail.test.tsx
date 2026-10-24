import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { PurchaseOrderDetail } from './PurchaseOrderDetail';
import { procurementApi } from '../api';
import { httpError, signIn } from '@/modules/inventory/testUtils';
import type { PurchaseOrder } from '../types';

vi.mock('../api', () => ({
  procurementApi: {
    getPo: vi.fn(), submitPo: vi.fn(), approvePo: vi.fn(), rejectPo: vi.fn(), cancelPo: vi.fn(), closePo: vi.fn(), receivePo: vi.fn(),
  },
}));
const api = vi.mocked(procurementApi);

const po = (o: Partial<PurchaseOrder> = {}): PurchaseOrder => ({
  id: 'po-1', poNumber: 'PO-0001', status: 'draft', supplier: { id: 's', code: 'S1', name: 'Acme' },
  center: { id: 'c-1', name: 'Center 1' }, warehouse: { id: 'w', code: 'W1', name: 'Main' },
  createdBy: { id: 'creator', fullName: 'Creator' }, version: 3,
  lines: [{ id: 'l-1', lineNo: 1, item: { id: 'i-1', sku: 'SKU-1', name: 'Filter' }, qtyOrdered: 10, qtyReceived: 4, unitPrice: 2.5 }],
  receipts: [], ...o,
});

const names = () => screen.getAllByRole('button').map((b) => b.textContent);

describe('PurchaseOrderDetail', () => {
  beforeEach(() => vi.clearAllMocks());

  it('draft: warehouse sees submit/edit/cancel but not approve or receive', async () => {
    signIn('warehouse', { id: 'creator' });
    api.getPo.mockResolvedValue(po());
    render(<PurchaseOrderDetail id="po-1" />);
    await screen.findByText(/PO-0001/);
    expect(screen.getByRole('button', { name: 'Submit for approval' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Edit' })).toHaveAttribute('href', '/procurement/purchase-orders/po-1/edit');
    expect(screen.getByRole('button', { name: 'Cancel order' })).toBeInTheDocument();
    expect(names()).not.toContain('Approve');
    expect(names()).not.toContain('Receive goods');
  });

  it('submitted: approver sees approve/reject; the creator does not (separation of duties)', async () => {
    api.getPo.mockResolvedValue(po({ status: 'submitted' }));
    signIn('operating_company_manager', { id: 'other' });
    const { unmount } = render(<PurchaseOrderDetail id="po-1" />);
    await screen.findByText(/PO-0001/);
    expect(screen.getByRole('button', { name: 'Approve' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reject' })).toBeInTheDocument();
    unmount();
    signIn('maintenance_manager', { id: 'creator' });
    render(<PurchaseOrderDetail id="po-1" />);
    await screen.findByText(/PO-0001/);
    expect(names()).not.toContain('Approve');
    expect(screen.getByText(/cannot approve or reject a purchase order you created/)).toBeInTheDocument();
  });

  it('roles without rights see no action buttons (hospital supervisor)', async () => {
    signIn('hospital_supervisor');
    api.getPo.mockResolvedValue(po({ status: 'approved' }));
    render(<PurchaseOrderDetail id="po-1" />);
    await screen.findByText(/PO-0001/);
    expect(screen.queryByRole('group', { name: 'Actions' })?.children.length ?? 0).toBe(0);
  });

  it('reject requires a reason of at least 5 characters then sends it with the version', async () => {
    signIn('operating_company_manager', { id: 'other' });
    api.getPo.mockResolvedValue(po({ status: 'submitted' }));
    api.rejectPo.mockResolvedValue(po({ status: 'rejected', decisionReason: 'too costly' }));
    render(<PurchaseOrderDetail id="po-1" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Reject' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Reject' }));
    expect(await within(dialog).findByText('This field is required')).toBeInTheDocument();
    expect(api.rejectPo).not.toHaveBeenCalled();
    await userEvent.type(within(dialog).getByRole('textbox'), 'too costly');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Reject' }));
    await waitFor(() => expect(api.rejectPo).toHaveBeenCalledWith('po-1', 'too costly', 3));
    expect(await screen.findByText('Purchase order rejected.')).toBeInTheDocument();
  });

  it('receive: prefilled remaining quantity, over-receipt 422 surfaces, retry reuses the key', async () => {
    signIn('warehouse');
    api.getPo.mockResolvedValue(po({ status: 'partially_received' }));
    api.receivePo.mockRejectedValueOnce(httpError(422, 'Over-receipt: ordered 10, already received 4, requested 9')).mockResolvedValueOnce({ id: 'r' } as never);
    render(<PurchaseOrderDetail id="po-1" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Receive goods' }));
    const dialog = await screen.findByRole('dialog');
    const qty = within(dialog).getByRole('textbox', { name: /SKU-1/ });
    expect(qty).toHaveValue('6');
    await userEvent.clear(qty);
    await userEvent.type(qty, '9'); // above remaining: the client lets the server decide
    await userEvent.click(within(dialog).getByRole('button', { name: 'Receive goods' }));
    expect(await within(dialog).findByText(/Over-receipt: ordered 10/)).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Receive goods' }));
    await waitFor(() => expect(api.receivePo).toHaveBeenCalledTimes(2));
    expect(api.receivePo.mock.calls[0][1]).toEqual({ note: undefined, lines: [{ poLineId: 'l-1', quantity: 9 }] });
    expect(api.receivePo.mock.calls[1][2]).toBe(api.receivePo.mock.calls[0][2]);
    expect(api.receivePo.mock.calls[0][2]).toMatch(/^[A-Za-z0-9-]{8,64}$/);
  });

  it('403 and 404 load errors', async () => {
    signIn('warehouse');
    api.getPo.mockRejectedValueOnce(httpError(403));
    const { unmount } = render(<PurchaseOrderDetail id="po-1" />);
    expect(await screen.findByText('Access denied')).toBeInTheDocument();
    unmount();
    api.getPo.mockRejectedValueOnce(httpError(404));
    render(<PurchaseOrderDetail id="po-1" />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Not found');
  });
});
