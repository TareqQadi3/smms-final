import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SuppliersPage } from './SuppliersPage';
import { procurementApi } from '../api';
import { httpError, page, signIn } from '@/modules/inventory/testUtils';

vi.mock('../api', () => ({
  procurementApi: { listSuppliers: vi.fn(), createSupplier: vi.fn(), updateSupplier: vi.fn(), deactivateSupplier: vi.fn() },
}));
const api = vi.mocked(procurementApi);
const sup = { id: 's-1', code: 'S1', name: 'Acme', nameAr: 'أكمي', active: true, email: 'a@acme.test' };

describe('SuppliersPage', () => {
  beforeEach(() => vi.clearAllMocks());

  it('lists suppliers; warehouse role is read-only', async () => {
    signIn('warehouse');
    api.listSuppliers.mockResolvedValue(page([sup]) as never);
    render(<SuppliersPage />);
    expect(await screen.findByText('Acme')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'New supplier' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Deactivate' })).toBeNull();
  });

  it('empty and 403 states', async () => {
    signIn('maintenance_manager');
    api.listSuppliers.mockResolvedValueOnce(page([]) as never);
    const { unmount } = render(<SuppliersPage />);
    expect(await screen.findByText('No suppliers')).toBeInTheDocument();
    unmount();
    api.listSuppliers.mockRejectedValueOnce(httpError(403));
    render(<SuppliersPage />);
    expect(await screen.findByText('Access denied')).toBeInTheDocument();
  });

  it('validates code, name and email, then creates', async () => {
    signIn('operating_company_manager');
    api.listSuppliers.mockResolvedValue(page([]) as never);
    api.createSupplier.mockResolvedValue(sup as never);
    render(<SuppliersPage />);
    await userEvent.click(await screen.findByRole('button', { name: 'New supplier' }));
    await screen.findByRole('dialog');
    await userEvent.type(screen.getByLabelText(/^Email/), 'not-an-email');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Invalid email address')).toBeInTheDocument();
    expect(screen.getAllByText('This field is required')).toHaveLength(2);
    expect(api.createSupplier).not.toHaveBeenCalled();
    await userEvent.type(screen.getByLabelText(/^Code/), 'S1');
    await userEvent.type(screen.getByLabelText(/English name/), 'Acme');
    const email = screen.getByLabelText(/^Email/);
    await userEvent.clear(email);
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(api.createSupplier).toHaveBeenCalled());
    expect(api.createSupplier.mock.calls[0][0]).toMatchObject({ code: 'S1', name: 'Acme' });
    expect(await screen.findByText('Supplier saved.')).toBeInTheDocument();
  });

  it('deactivate asks for confirmation and shows server errors', async () => {
    signIn('maintenance_manager');
    api.listSuppliers.mockResolvedValue(page([sup]) as never);
    api.deactivateSupplier.mockRejectedValue(httpError(409, 'Supplier has open purchase orders'));
    render(<SuppliersPage />);
    await userEvent.click(await screen.findByRole('button', { name: 'Deactivate' }));
    const dialog = await screen.findByRole('dialog');
    expect(api.deactivateSupplier).not.toHaveBeenCalled();
    await userEvent.click(screen.getAllByRole('button', { name: 'Deactivate' }).at(-1)!);
    expect(await screen.findByText(/Supplier has open purchase orders/)).toBeInTheDocument();
    expect(dialog).toBeDefined();
  });
});
