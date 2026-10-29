import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ItemsList } from './ItemsList';
import { inventoryApi } from '../api';
import { httpError, page, signIn } from '../testUtils';

vi.mock('../api', () => ({
  inventoryApi: { listItems: vi.fn(), createItem: vi.fn(), listUoms: vi.fn() },
}));
const api = vi.mocked(inventoryApi);

const item = (o: Record<string, unknown> = {}) => ({
  id: 'i-1', sku: 'SKU-1', name: 'Filter', nameAr: 'فلتر', category: 'HVAC', uom: 'pcs', reorderLevel: 5,
  totalOnHand: 3, totalReserved: 1, totalAvailable: 2, lowStock: true, active: true, ...o,
});

describe('ItemsList', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.listUoms.mockResolvedValue(page([]) as never);
  });

  it('renders loading then rows with a low-stock badge (English)', async () => {
    signIn('warehouse');
    api.listItems.mockResolvedValue(page([item()]) as never);
    render(<ItemsList />);
    expect(screen.getByRole('status')).toHaveAttribute('aria-busy', 'true');
    expect(await screen.findByRole('link', { name: 'Filter' })).toHaveAttribute('href', '/inventory/i-1');
    expect(screen.getByText('Low stock')).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'On hand' })).toBeInTheDocument();
  });

  it('shows Arabic names and headings by default', async () => {
    signIn('warehouse', { lang: 'ar' });
    api.listItems.mockResolvedValue(page([item()]) as never);
    render(<ItemsList />);
    expect(await screen.findByRole('link', { name: 'فلتر' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'الأصناف' })).toBeInTheDocument();
  });

  it('empty state and error state', async () => {
    signIn('warehouse');
    api.listItems.mockResolvedValueOnce(page([]) as never);
    const { unmount } = render(<ItemsList />);
    expect(await screen.findByText('No items')).toBeInTheDocument();
    unmount();
    api.listItems.mockRejectedValueOnce(httpError(500));
    render(<ItemsList />);
    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });

  it('403 shows PermissionDenied', async () => {
    signIn('warehouse');
    api.listItems.mockRejectedValue(httpError(403));
    render(<ItemsList />);
    expect(await screen.findByText('Access denied')).toBeInTheDocument();
  });

  it('hides create for roles that cannot write the catalog and hides stock columns for technicians', async () => {
    signIn('technician');
    api.listItems.mockResolvedValue(page([item({ totalOnHand: undefined, totalAvailable: undefined, totalReserved: undefined })]) as never);
    render(<ItemsList />);
    await screen.findByRole('link', { name: 'Filter' });
    expect(screen.queryByRole('button', { name: 'New item' })).toBeNull();
    expect(screen.queryByRole('columnheader', { name: 'On hand' })).toBeNull();
  });

  it('create dialog validates, then posts without any quantity field', async () => {
    signIn('maintenance_manager');
    api.listItems.mockResolvedValue(page([item()]) as never);
    api.createItem.mockResolvedValue(item({ id: 'i-2', name: 'Belt', nameAr: 'سير' }) as never);
    render(<ItemsList />);
    await screen.findByRole('link', { name: 'Filter' });
    await userEvent.click(screen.getByRole('button', { name: 'New item' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect((await screen.findAllByRole('alert')).length).toBeGreaterThanOrEqual(3);
    expect(api.createItem).not.toHaveBeenCalled();
    expect(dialog.textContent).not.toMatch(/On hand|Opening quantity/);

    await userEvent.type(screen.getByLabelText(/SKU/), 'bad sku!');
    await userEvent.type(screen.getByLabelText(/Arabic name/), 'سير');
    await userEvent.type(screen.getByLabelText(/English name/), 'Belt');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Letters, digits and . _ / - only')).toBeInTheDocument();

    const sku = screen.getByLabelText(/SKU/);
    await userEvent.clear(sku);
    await userEvent.type(sku, 'BELT-1');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(api.createItem).toHaveBeenCalledTimes(1));
    expect(api.createItem.mock.calls[0][0]).toMatchObject({ centerId: 'c-1', sku: 'BELT-1', name: 'Belt', nameAr: 'سير', reorderLevel: 0 });
    expect(api.createItem.mock.calls[0][0]).not.toHaveProperty('quantity');
    expect(await screen.findByText(/Item saved: Belt/)).toBeInTheDocument();
  });
});
