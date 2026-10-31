import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useAuthStore } from '@/store/authStore';
import { EscalationPanel } from './EscalationPanel';
import type { WorkOrderDetail } from '@/modules/workflow/types';

const list = vi.fn();
const escalate = vi.fn();
vi.mock('@/modules/workflow/api', async (orig) => ({
  ...(await orig<typeof import('@/modules/workflow/api')>()),
  workflowApi: { escalations: (...a: unknown[]) => list(...a), escalate: (...a: unknown[]) => escalate(...a) },
}));

const wo = (o: Partial<WorkOrderDetail> = {}) =>
  ({ id: 'w1', ticketNumber: 'T-1', title: 't', status: 'in_progress', priority: 'high', subType: 'x', createdAt: '2026-01-01T00:00:00Z', escalationLevel: 1, ...o }) as WorkOrderDetail;

beforeEach(() => { list.mockReset(); escalate.mockReset(); act(() => useAuthStore.setState({ lang: 'en' })); });

describe('EscalationPanel', () => {
  it('lists the history with trigger, level and actor', async () => {
    list.mockResolvedValue({ data: [
      { id: 'e1', level: 1, trigger: 'sla_breach', triggeredBy: null, notifiedCount: 3, createdAt: '2026-01-02T10:00:00Z' },
      { id: 'e2', level: 2, trigger: 'manual', triggeredBy: { id: 'u', fullName: 'Sara' }, reason: 'still broken', createdAt: '2026-01-03T10:00:00Z' },
    ] });
    render(<EscalationPanel wo={wo({ escalationLevel: 2 })} role="control_room" onChanged={() => {}} />);
    expect(await screen.findByText('SLA breached')).toBeInTheDocument();
    expect(screen.getByText('Manual')).toBeInTheDocument();
    expect(screen.getByText('still broken')).toBeInTheDocument();
    expect(screen.getByText('by Sara')).toBeInTheDocument();
  });

  it('shows an empty state and an error state with retry', async () => {
    list.mockResolvedValueOnce({ data: [] });
    const { unmount } = render(<EscalationPanel wo={wo({ escalationLevel: 0 })} role="control_room" onChanged={() => {}} />);
    expect(await screen.findByText('No escalations for this work order')).toBeInTheDocument();
    unmount();
    list.mockRejectedValueOnce({ response: { status: 500 } }).mockResolvedValueOnce({ data: [] });
    render(<EscalationPanel wo={wo()} role="control_room" onChanged={() => {}} />);
    await userEvent.click(await screen.findByRole('button', { name: /retry/i }));
    expect(await screen.findByText('No escalations for this work order')).toBeInTheDocument();
  });

  it('does not render or call the API for requesters', () => {
    const { container } = render(<EscalationPanel wo={wo()} role="resident" onChanged={() => {}} />);
    expect(container).toBeEmptyDOMElement();
    expect(list).not.toHaveBeenCalled();
  });

  it('manual escalation needs a reason and refreshes the list', async () => {
    list.mockResolvedValue({ data: [] });
    escalate.mockResolvedValue({ data: {} });
    const onChanged = vi.fn();
    render(<EscalationPanel wo={wo()} role="maintenance_manager" onChanged={onChanged} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Escalate manually' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.type(dialog.querySelector('textarea')!, 'customer waiting for hours');
    await userEvent.click(dialog.querySelector('button[type="submit"]')!);
    await waitFor(() => expect(escalate).toHaveBeenCalledWith('w1', 'customer waiting for hours'));
    await waitFor(() => expect(onChanged).toHaveBeenCalled());
  });

  it('hides the escalate button when paused or at the maximum level and explains why', async () => {
    list.mockResolvedValue({ data: [] });
    const { rerender } = render(<EscalationPanel wo={wo({ escalationLevel: 3 })} role="control_room" onChanged={() => {}} />);
    expect(screen.queryByRole('button', { name: 'Escalate manually' })).toBeNull();
    expect(screen.getByText('Highest escalation level reached')).toBeInTheDocument();
    rerender(<EscalationPanel wo={wo({ slaPaused: true })} role="control_room" onChanged={() => {}} />);
    expect(screen.getByText('Cannot escalate while the SLA is paused')).toBeInTheDocument();
  });
});
