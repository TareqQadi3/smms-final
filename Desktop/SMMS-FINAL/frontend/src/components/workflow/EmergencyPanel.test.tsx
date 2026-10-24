import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useAuthStore } from '@/store/authStore';
import { EmergencyPanel } from './EmergencyPanel';
import type { WorkOrderDetail } from '@/modules/workflow/types';

const declare = vi.fn();
const clear = vi.fn();
vi.mock('@/modules/workflow/api', async (orig) => ({
  ...(await orig<typeof import('@/modules/workflow/api')>()),
  workflowApi: { declareEmergency: (...a: unknown[]) => declare(...a), clearEmergency: (...a: unknown[]) => clear(...a) },
}));

const wo = (o: Partial<WorkOrderDetail> = {}) =>
  ({ id: 'w1', ticketNumber: 'T-1', title: 't', status: 'in_progress', priority: 'high', subType: 'x', createdAt: '2026-01-01T00:00:00Z', ...o }) as WorkOrderDetail;

beforeEach(() => { declare.mockReset(); clear.mockReset(); act(() => useAuthStore.setState({ lang: 'en' })); });

describe('EmergencyPanel', () => {
  it('renders nothing for roles that cannot declare when there is no emergency', () => {
    const { container } = render(<EmergencyPanel wo={wo()} role="technician" onChanged={() => {}} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('declares through a confirm dialog that requires a reason of 5+ characters', async () => {
    declare.mockResolvedValue({ data: {} });
    const onChanged = vi.fn();
    render(<EmergencyPanel wo={wo()} role="control_room" onChanged={onChanged} />);
    await userEvent.click(screen.getByRole('button', { name: 'Declare emergency' }));
    const dialog = await screen.findByRole('dialog', { name: 'Declare an emergency' });
    const confirm = dialog.querySelector('button[type="submit"]') as HTMLButtonElement;
    await userEvent.type(dialog.querySelector('textarea')!, 'abc');
    await userEvent.click(confirm);
    expect(declare).not.toHaveBeenCalled();
    expect(await screen.findByText('Enter a reason of at least 5 characters')).toBeInTheDocument();
    await userEvent.type(dialog.querySelector('textarea')!, 'def gas leak');
    await userEvent.click(confirm);
    await waitFor(() => expect(declare).toHaveBeenCalledWith('w1', 'abcdef gas leak'));
    await waitFor(() => expect(onChanged).toHaveBeenCalled());
  });

  it('shows the emergency state and a clear button only for roles that may clear', async () => {
    const active = wo({ isEmergency: true, emergencyDeclaredAt: '2026-01-02T10:00:00Z' });
    const { rerender } = render(<EmergencyPanel wo={active} role="control_room" onChanged={() => {}} />);
    expect(screen.getByRole('status')).toHaveTextContent('Emergency');
    expect(screen.queryByRole('button', { name: 'Clear emergency' })).toBeNull();
    rerender(<EmergencyPanel wo={active} role="maintenance_manager" onChanged={() => {}} />);
    expect(screen.getByRole('button', { name: 'Clear emergency' })).toBeInTheDocument();
  });

  it('409 from the server shows the reload message and calls onConflict', async () => {
    declare.mockRejectedValue({ response: { status: 409, data: { message: 'modified concurrently' } } });
    const onConflict = vi.fn();
    render(<EmergencyPanel wo={wo()} role="system_admin" onChanged={() => {}} onConflict={onConflict} />);
    await userEvent.click(screen.getByRole('button', { name: 'Declare emergency' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.type(dialog.querySelector('textarea')!, 'fire in the plant room');
    await userEvent.click(dialog.querySelector('button[type="submit"]')!);
    await waitFor(() => expect(onConflict).toHaveBeenCalled());
  });
});
