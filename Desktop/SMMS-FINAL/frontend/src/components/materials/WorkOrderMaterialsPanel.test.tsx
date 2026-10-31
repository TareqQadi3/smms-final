import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { WorkOrderMaterialsPanel } from './WorkOrderMaterialsPanel';
import { materialsApi } from '@/modules/materials/api';
import { inventoryApi } from '@/modules/inventory/api';
import { httpError, page, signIn } from '@/modules/inventory/testUtils';

vi.mock('@/modules/materials/api', () => ({ materialsApi: { listForWorkOrder: vi.fn(), create: vi.fn() } }));
vi.mock('@/modules/inventory/api', () => ({ inventoryApi: { listWarehouses: vi.fn(), listItems: vi.fn() } }));
const api = vi.mocked(materialsApi);
const inv = vi.mocked(inventoryApi);

const WH = '11111111-2222-3333-4444-555555555555';

describe('WorkOrderMaterialsPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    inv.listItems.mockResolvedValue(page([{ id: 'i-1', sku: 'SKU-1', name: 'Filter', active: true }]) as never);
    inv.listWarehouses.mockResolvedValue(page([]) as never);
  });

  it('empty state and create button for a technician on an in-progress order', async () => {
    signIn('technician');
    api.listForWorkOrder.mockResolvedValue([]);
    render(<WorkOrderMaterialsPanel workOrderId="wo-1" workOrderStatus="in_progress" />);
    expect(await screen.findByText('No material requests for this work order.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'New material request' })).toBeInTheDocument();
  });

  it('no create button when the work order is not active, or for read-only roles', async () => {
    signIn('technician');
    api.listForWorkOrder.mockResolvedValue([]);
    const a = render(<WorkOrderMaterialsPanel workOrderId="wo-1" workOrderStatus="completed" />);
    await screen.findByText('No material requests for this work order.');
    expect(screen.queryByRole('button', { name: 'New material request' })).toBeNull();
    a.unmount();
    signIn('warehouse');
    render(<WorkOrderMaterialsPanel workOrderId="wo-1" workOrderStatus="in_progress" />);
    await screen.findByText('No material requests for this work order.');
    expect(screen.queryByRole('button', { name: 'New material request' })).toBeNull();
  });

  it('lists requests with links and status', async () => {
    signIn('control_room');
    api.listForWorkOrder.mockResolvedValue([{ id: 'mr-1', requestNumber: 'MR-1', status: 'issued', warehouseId: WH, createdAt: '2026-10-01T00:00:00Z' }]);
    render(<WorkOrderMaterialsPanel workOrderId="wo-1" />);
    expect(await screen.findByRole('link', { name: 'MR-1' })).toHaveAttribute('href', '/material-requests/mr-1');
    expect(screen.getByText('Issued')).toBeInTheDocument();
  });

  it('403 shows PermissionDenied; other errors offer retry', async () => {
    signIn('technician');
    api.listForWorkOrder.mockRejectedValueOnce(httpError(404));
    const a = render(<WorkOrderMaterialsPanel workOrderId="wo-1" />);
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    a.unmount();
    api.listForWorkOrder.mockRejectedValueOnce(httpError(403));
    render(<WorkOrderMaterialsPanel workOrderId="wo-1" />);
    expect(await screen.findByText('Access denied')).toBeInTheDocument();
  });

  it('technician creates a request: validates, manual warehouse id fallback, posts', async () => {
    signIn('technician');
    api.listForWorkOrder.mockResolvedValue([]);
    api.create.mockResolvedValue({ id: 'mr-2', requestNumber: 'MR-2' } as never);
    render(<WorkOrderMaterialsPanel workOrderId="wo-1" workOrderStatus="in_progress" />);
    await userEvent.click(await screen.findByRole('button', { name: 'New material request' }));
    await screen.findByRole('dialog');
    expect(inv.listWarehouses).not.toHaveBeenCalled(); // technicians cannot list warehouses
    await userEvent.click(screen.getByRole('button', { name: 'Submit request' }));
    expect((await screen.findAllByRole('alert')).length).toBeGreaterThanOrEqual(3);
    expect(api.create).not.toHaveBeenCalled();
    await userEvent.type(screen.getByLabelText(/Warehouse id/), 'not-a-uuid');
    await userEvent.click(screen.getByRole('button', { name: 'Submit request' }));
    expect(await screen.findByText('Invalid warehouse id')).toBeInTheDocument();
    const wh = screen.getByLabelText(/Warehouse id/);
    await userEvent.clear(wh);
    await userEvent.type(wh, WH);
    await userEvent.selectOptions(await screen.findByRole('combobox', { name: /Item 1/ }), 'i-1');
    await userEvent.type(screen.getByLabelText(/^Quantity/), '2');
    await userEvent.click(screen.getByRole('button', { name: 'Submit request' }));
    await waitFor(() => expect(api.create).toHaveBeenCalled());
    expect(api.create).toHaveBeenCalledWith('wo-1', { warehouseId: WH, note: undefined, lines: [{ itemId: 'i-1', quantity: 2 }] });
    expect(await screen.findByText('Request MR-2 created.')).toBeInTheDocument();
  });

  it('shows the server 422 when the work order is not active', async () => {
    signIn('maintenance_manager');
    api.listForWorkOrder.mockResolvedValue([]);
    inv.listWarehouses.mockResolvedValue(page([{ id: WH, code: 'W1', name: 'Main', active: true, centerId: 'c-1' }]) as never);
    api.create.mockRejectedValue(httpError(422, 'Materials can only be requested while the work order is in_progress or waiting_materials'));
    render(<WorkOrderMaterialsPanel workOrderId="wo-1" />);
    await userEvent.click(await screen.findByRole('button', { name: 'New material request' }));
    await userEvent.selectOptions(await screen.findByLabelText(/^Warehouse/), WH);
    await userEvent.selectOptions(await screen.findByRole('combobox', { name: /Item 1/ }), 'i-1');
    await userEvent.type(screen.getByLabelText(/^Quantity/), '1');
    await userEvent.click(screen.getByRole('button', { name: 'Submit request' }));
    expect(await screen.findByText(/Materials can only be requested/)).toBeInTheDocument();
  });
});
