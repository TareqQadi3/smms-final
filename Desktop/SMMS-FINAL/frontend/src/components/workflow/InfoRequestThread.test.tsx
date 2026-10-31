import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { useAuthStore } from '@/store/authStore';
import { InfoRequestThread } from './InfoRequestThread';
import type { WorkOrderDetail } from '@/modules/workflow/types';

const list = vi.fn();
vi.mock('@/modules/workflow/api', async (orig) => ({
  ...(await orig<typeof import('@/modules/workflow/api')>()),
  workflowApi: { infoRequests: (...a: unknown[]) => list(...a) },
}));
const wo = (o: Partial<WorkOrderDetail> = {}) =>
  ({ id: 'w1', ticketNumber: 'T-1', title: 't', status: 'under_review', priority: 'high', subType: 'x', createdAt: '2026-01-01T00:00:00Z', ...o }) as WorkOrderDetail;

beforeEach(() => { list.mockReset(); act(() => useAuthStore.setState({ lang: 'en' })); });

describe('InfoRequestThread', () => {
  it('shows question, answer, pause duration and the SLA paused indicator', async () => {
    list.mockResolvedValue({ data: [
      { id: 'i1', question: 'Which room?', requestedBy: { id: 'a', fullName: 'Omar' }, requestedAt: '2026-01-02T10:00:00Z',
        answer: 'Room 305', answeredBy: { id: 'b', fullName: 'Lina' }, answeredAt: '2026-01-02T12:00:00Z', pausedSeconds: 7200 },
      { id: 'i2', question: 'Photo please', requestedBy: { id: 'a', fullName: 'Omar' }, requestedAt: '2026-01-03T10:00:00Z' },
    ] });
    render(<InfoRequestThread wo={wo({ slaPaused: true, slaPausedSeconds: 7200 })} />);
    expect(await screen.findByText('Which room?')).toBeInTheDocument();
    expect(screen.getByText('Room 305')).toBeInTheDocument();
    expect(screen.getByText('Paused for: 2 h')).toBeInTheDocument();
    expect(screen.getByText('Waiting for the requester')).toBeInTheDocument();
    expect(screen.getAllByText('SLA clock paused').length).toBeGreaterThan(0);
  });

  it('empty and error states', async () => {
    list.mockResolvedValueOnce({ data: [] });
    const { unmount } = render(<InfoRequestThread wo={wo()} />);
    expect(await screen.findByText('No information requests')).toBeInTheDocument();
    expect(screen.queryByText('SLA clock paused')).toBeNull();
    unmount();
    list.mockRejectedValueOnce({ response: { status: 403 } });
    render(<InfoRequestThread wo={wo()} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('You do not have permission');
  });

  it('renders Arabic by default', async () => {
    act(() => useAuthStore.setState({ lang: 'ar' }));
    list.mockResolvedValue({ data: [] });
    render(<InfoRequestThread wo={wo()} />);
    expect(await screen.findByText('لا توجد طلبات معلومات')).toBeInTheDocument();
  });
});
