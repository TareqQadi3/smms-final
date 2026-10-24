import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { useAuthStore } from '@/store/authStore';
import { ConfirmationRating } from './ConfirmationRating';
import type { WorkOrderDetail } from '@/modules/workflow/types';

const rating = vi.fn();
vi.mock('@/modules/workflow/api', async (orig) => ({
  ...(await orig<typeof import('@/modules/workflow/api')>()),
  workflowApi: { rating: (...a: unknown[]) => rating(...a) },
}));
const wo = (o: Partial<WorkOrderDetail> = {}) =>
  ({ id: 'w1', ticketNumber: 'T-1', title: 't', status: 'closed', priority: 'high', subType: 'x', createdAt: '2026-01-01T00:00:00Z', ...o }) as WorkOrderDetail;

beforeEach(() => { rating.mockReset(); act(() => useAuthStore.setState({ lang: 'en' })); });

describe('ConfirmationRating', () => {
  it('renders nothing before the work is completed', () => {
    const { container } = render(<ConfirmationRating wo={wo({ status: 'in_progress' })} role="control_room" />);
    expect(container).toBeEmptyDOMElement();
  });
  it('shows accepted confirmation with the rating and comment', async () => {
    rating.mockResolvedValue({ data: { id: 'r', score: 4, comment: 'Quick fix', createdAt: '2026-01-05T00:00:00Z' } });
    render(<ConfirmationRating wo={wo({ requesterConfirmation: 'accepted', requesterConfirmedAt: '2026-01-04T08:00:00Z' })} role="maintenance_manager" />);
    expect(screen.getByText('The requester accepted the work')).toBeInTheDocument();
    expect(await screen.findByText('4 out of 5')).toBeInTheDocument();
    expect(screen.getByText('Quick fix')).toBeInTheDocument();
  });
  it('shows disputed state and treats a 404 rating as not rated yet', async () => {
    rating.mockRejectedValue({ response: { status: 404 } });
    render(<ConfirmationRating wo={wo({ requesterConfirmation: 'disputed' })} role="control_room" />);
    expect(screen.getByText('The requester disputed the work')).toBeInTheDocument();
    expect(await screen.findByText('This work order has not been rated yet')).toBeInTheDocument();
  });
  it('completed without confirmation is pending; technicians never request the rating', () => {
    render(<ConfirmationRating wo={wo({ status: 'completed' })} role="technician" />);
    expect(screen.getByText('Waiting for requester confirmation')).toBeInTheDocument();
    expect(rating).not.toHaveBeenCalled();
  });
});
