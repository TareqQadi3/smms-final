import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ReceiptForm } from './ReceiptForm';
import { inventoryApi } from '../api';
import { httpError, page, signIn } from '../testUtils';

vi.mock('../api', () => ({
  inventoryApi: { listWarehouses: vi.fn(), listItems: vi.fn(), receipt: vi.fn() },
}));
const api = vi.mocked(inventoryApi);

const wh = { id: 'w-1', centerId: 'c-1', code: 'WH1', name: 'Main', nameAr: 'الرئيسي', active: true };
const item = { id: 'i-1', sku: 'SKU-1', name: 'Filter', nameAr: 'فلتر', uom: 'pcs', active: true };

async function fill(qty: string) {
  await userEvent.selectOptions(await screen.findByLabelText(/Warehouse/), 'w-1');
  await userEvent.selectOptions(await screen.findByRole('combobox', { name: /Item 1/ }), 'i-1');
  await userEvent.type(screen.getByLabelText(/Quantity/), qty);
}

describe('ReceiptForm', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    signIn('warehouse');
    api.listWarehouses.mockResolvedValue(page([wh]) as never);
    api.listItems.mockResolvedValue(page([item]) as never);
  });

  it('shows validation errors and does not call the API', async () => {
    render(<ReceiptForm />);
    await screen.findByLabelText(/Warehouse/);
    await userEvent.click(screen.getByRole('button', { name: 'Post receipt' }));
    expect((await screen.findAllByRole('alert')).length).toBeGreaterThanOrEqual(3);
    expect(api.receipt).not.toHaveBeenCalled();
  });

  it('rejects more than 3 decimals and zero', async () => {
    render(<ReceiptForm />);
    await fill('1.2345');
    await userEvent.click(screen.getByRole('button', { name: 'Post receipt' }));
    expect(await screen.findByText('At most 3 decimals')).toBeInTheDocument();
    expect(api.receipt).not.toHaveBeenCalled();
  });

  it('posts the receipt with a UUID Idempotency-Key and shows success', async () => {
    api.receipt.mockResolvedValue({ movements: [{ id: 'm-1' }] } as never);
    render(<ReceiptForm />);
    await fill('5');
    await userEvent.click(screen.getByRole('button', { name: 'Post receipt' }));
    await screen.findByText(/Receipt posted \(1 movements\)/);
    const [body, key] = api.receipt.mock.calls[0];
    expect(body).toMatchObject({ warehouseId: 'w-1', lines: [{ itemId: 'i-1', quantity: 5 }] });
    expect(key).toMatch(/^[A-Za-z0-9-]{8,64}$/);
  });

  it('reuses the same key when the user retries after a 409 and surfaces the message', async () => {
    api.receipt.mockRejectedValueOnce(httpError(409, 'The system is busy, retry in a moment')).mockResolvedValueOnce({ movements: [] } as never);
    render(<ReceiptForm />);
    await fill('5');
    await userEvent.click(screen.getByRole('button', { name: 'Post receipt' }));
    expect(await screen.findByText(/The system is busy/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Post receipt' }));
    await waitFor(() => expect(api.receipt).toHaveBeenCalledTimes(2));
    expect(api.receipt.mock.calls[1][1]).toBe(api.receipt.mock.calls[0][1]);
  });

  it('uses a fresh key after a successful post and for a new intent', async () => {
    api.receipt.mockResolvedValue({ movements: [{ id: 'm' }] } as never);
    render(<ReceiptForm />);
    await fill('5');
    await userEvent.click(screen.getByRole('button', { name: 'Post receipt' }));
    await screen.findByText(/Receipt posted/);
    await userEvent.selectOptions(await screen.findByRole('combobox', { name: /Item 1/ }), 'i-1');
    await userEvent.type(screen.getByLabelText(/Quantity/), '5');
    await userEvent.click(screen.getByRole('button', { name: 'Post receipt' }));
    await waitFor(() => expect(api.receipt).toHaveBeenCalledTimes(2));
    expect(api.receipt.mock.calls[1][1]).not.toBe(api.receipt.mock.calls[0][1]);
  });

  it('shows PermissionDenied for roles that cannot receive (technician)', () => {
    signIn('technician');
    render(<ReceiptForm />);
    expect(screen.getByRole('alert')).toHaveTextContent('Access denied');
    expect(api.listWarehouses).not.toHaveBeenCalled();
  });

  it('renders in Arabic by default with rtl-safe labels', async () => {
    signIn('warehouse', { lang: 'ar' });
    render(<ReceiptForm />);
    expect(await screen.findByRole('heading', { name: 'سند استلام' })).toBeInTheDocument();
  });
});
