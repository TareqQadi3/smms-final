import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { PurchaseOrderForm } from './PurchaseOrderForm';
import { procurementApi } from '../api';
import { inventoryApi } from '@/modules/inventory/api';
import { httpError, page, signIn } from '@/modules/inventory/testUtils';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push }), usePathname: () => '/' }));
vi.mock('../api', () => ({ procurementApi: { listSuppliers: vi.fn(), createPo: vi.fn(), updatePo: vi.fn(), getPo: vi.fn() } }));
vi.mock('@/modules/inventory/api', () => ({ inventoryApi: { listWarehouses: vi.fn(), listItems: vi.fn() } }));
const api = vi.mocked(procurementApi);
const inv = vi.mocked(inventoryApi);

async function fillValid() {
  await userEvent.selectOptions(await screen.findByLabelText(/^Warehouse/), 'w-1');
  await userEvent.selectOptions(screen.getByLabelText(/^Supplier/), 's-1');
  await userEvent.selectOptions(await screen.findByRole('combobox', { name: /Item 1/ }), 'i-1');
  await userEvent.type(screen.getByLabelText(/Qty ordered/), '4');
  await userEvent.type(screen.getByLabelText(/Unit price/), '12.5');
}

describe('PurchaseOrderForm', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    signIn('warehouse');
    api.listSuppliers.mockResolvedValue(page([{ id: 's-1', code: 'S1', name: 'Acme', active: true }]) as never);
    inv.listWarehouses.mockResolvedValue(page([{ id: 'w-1', code: 'W1', name: 'Main', centerId: 'c-1', active: true }]) as never);
    inv.listItems.mockResolvedValue(page([{ id: 'i-1', sku: 'SKU-1', name: 'Filter', active: true }]) as never);
  });

  it('validates required fields, bad price and duplicate items', async () => {
    render(<PurchaseOrderForm />);
    await screen.findByLabelText(/^Supplier/);
    await userEvent.click(screen.getByRole('button', { name: 'Save as draft' }));
    expect((await screen.findAllByRole('alert')).length).toBeGreaterThanOrEqual(4);
    expect(api.createPo).not.toHaveBeenCalled();
  });

  it('rejects an invalid unit price', async () => {
    render(<PurchaseOrderForm />);
    await fillValid();
    const price = screen.getByLabelText(/Unit price/);
    await userEvent.clear(price);
    await userEvent.type(price, '1.234');
    await userEvent.click(screen.getByRole('button', { name: 'Save as draft' }));
    expect(await screen.findByText(/Invalid price/)).toBeInTheDocument();
  });

  it('creates a draft and navigates to the detail page', async () => {
    api.createPo.mockResolvedValue({ id: 'po-9' } as never);
    render(<PurchaseOrderForm />);
    await fillValid();
    await userEvent.click(screen.getByRole('button', { name: 'Save as draft' }));
    await waitFor(() => expect(push).toHaveBeenCalledWith('/procurement/purchase-orders/po-9'));
    expect(api.createPo.mock.calls[0][0]).toMatchObject({ centerId: 'c-1', supplierId: 's-1', warehouseId: 'w-1', lines: [{ itemId: 'i-1', qtyOrdered: 4, unitPrice: 12.5 }] });
  });

  it('shows a 422 from the server and stays on the form', async () => {
    api.createPo.mockRejectedValue(httpError(422, 'Supplier is inactive'));
    render(<PurchaseOrderForm />);
    await fillValid();
    await userEvent.click(screen.getByRole('button', { name: 'Save as draft' }));
    expect(await screen.findByText(/Supplier is inactive/)).toBeInTheDocument();
    expect(push).not.toHaveBeenCalled();
  });

  it('permission denied for roles that cannot create purchase orders', () => {
    signIn('hospital_supervisor');
    render(<PurchaseOrderForm />);
    expect(screen.getByRole('alert')).toHaveTextContent('Access denied');
  });
});
