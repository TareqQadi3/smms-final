import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '@/store/authStore';
import type { ActionOutcome, ActionSpec, QueueItem } from '@/lib/offline/queue';
import type { TechWorkOrder } from '@/modules/tech/types';

const enqueue = vi.fn<(s: ActionSpec) => Promise<ActionOutcome>>();
let items: QueueItem[] = [];
vi.mock('@/lib/offline/useOfflineQueue', () => ({
  useOfflineQueue: () => ({ items, online: true, enqueue, retry: vi.fn(), discard: vi.fn() }),
  getQueue: () => ({ onEvent: () => () => {} }),
}));
vi.mock('next/link', () => ({ default: ({ href, children, ...r }: { href: string; children: React.ReactNode }) => <a href={href} {...r}>{children}</a> }));

import { ActionsPanel } from './ActionsPanel';
import { PendingActions } from './PendingActions';
import { WorkOrderCard } from './WorkOrderCard';

const wo = (over: Partial<TechWorkOrder> = {}): TechWorkOrder => ({
  id: 'w1', ticketNumber: 'WO-1', status: 'assigned', priority: 'high', title: 'Fix pump', createdAt: '2026-01-01T00:00:00Z', ...over,
});

beforeEach(() => { items = []; enqueue.mockReset(); });

describe('ActionsPanel', () => {
  it('assigned: only Accept/Start; it enqueues a transition', async () => {
    enqueue.mockResolvedValue({ state: 'done', item: {} as QueueItem });
    const onChanged = vi.fn();
    render(<ActionsPanel wo={wo()} hasAfterPhoto={false} onChanged={onChanged} />);
    expect(screen.queryByRole('button', { name: /إنهاء العمل/ })).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: /قبول وبدء العمل/ }));
    expect(enqueue.mock.calls[0][0]).toMatchObject({ kind: 'transition', url: '/work-orders/w1/transition', json: { targetStatus: 'in_progress' } });
    await waitFor(() => expect(onChanged).toHaveBeenCalled());
  });

  it('in_progress: completion needs a report, then sends it as the note', async () => {
    enqueue.mockResolvedValue({ state: 'queued', item: {} as QueueItem });
    render(<ActionsPanel wo={wo({ status: 'in_progress' })} hasAfterPhoto={false} onChanged={() => {}} />);
    await userEvent.click(screen.getByRole('button', { name: /إنهاء العمل/ }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(screen.getByRole('button', { name: 'إرسال' }));
    expect(await screen.findByText('التقرير مطلوب (5 أحرف على الأقل).')).toBeInTheDocument();
    expect(enqueue).not.toHaveBeenCalled();
    await userEvent.type(dialog.querySelector('textarea')!, 'Replaced the seal');
    await userEvent.click(screen.getByRole('button', { name: 'إرسال' }));
    expect(enqueue.mock.calls[0][0].json).toEqual({ targetStatus: 'completed', note: 'Replaced the seal' });
  });

  it('waiting_materials offers no technician action (the warehouse resumes it)', () => {
    render(<ActionsPanel wo={wo({ status: 'waiting_materials' })} hasAfterPhoto onChanged={() => {}} />);
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('shows the server refusal (422) with its own wording', async () => {
    enqueue.mockResolvedValue({ state: 'failed', item: {} as QueueItem, error: { class: 'conflict', status: 422, message: 'Transition not allowed' } });
    render(<ActionsPanel wo={wo()} hasAfterPhoto={false} onChanged={() => {}} />);
    await userEvent.click(screen.getByRole('button', { name: /قبول وبدء العمل/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('رفض النظام الإجراء');
    expect(screen.getByRole('alert')).toHaveTextContent('Transition not allowed');
  });

  it('disables the buttons while a transition for this ticket is already queued (no duplicate)', () => {
    items = [{ id: 'q', idempotencyKey: 'k', userId: 'u', createdAt: 0, attempts: 0, nextAttemptAt: 0, status: 'pending', kind: 'transition', workOrderId: 'w1', url: '/x' }];
    render(<ActionsPanel wo={wo()} hasAfterPhoto={false} onChanged={() => {}} />);
    expect(screen.getByRole('button', { name: /قبول وبدء العمل/ })).toBeDisabled();
  });
});

describe('PendingActions', () => {
  it('shows waiting and failed items; failed ones offer retry and discard', () => {
    items = [
      { id: 'a', idempotencyKey: 'k1', userId: 'u', createdAt: 0, attempts: 2, nextAttemptAt: 0, status: 'pending', kind: 'note', workOrderId: 'w1', url: '/x', meta: { excerpt: 'leak' } },
      { id: 'b', idempotencyKey: 'k2', userId: 'u', createdAt: 0, attempts: 1, nextAttemptAt: 0, status: 'failed', kind: 'transition', workOrderId: 'w1', url: '/x', meta: { target: 'completed' }, lastError: { class: 'conflict', status: 422, message: 'nope' } },
    ];
    render(<PendingActions workOrderId="w1" />);
    expect(screen.getAllByTestId('pending-item')).toHaveLength(2);
    expect(screen.getByText(/محاولة 3/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /إعادة المحاولة/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /حذف/ })).toBeInTheDocument();
  });

  it('renders nothing when the queue is empty', () => {
    const { container } = render(<PendingActions />);
    expect(container).toBeEmptyDOMElement();
  });
});

describe('WorkOrderCard', () => {
  it('is one link with an accessible name, in English too', () => {
    act(() => useAuthStore.setState({ lang: 'en' }));
    render(<WorkOrderCard wo={wo({ isEmergency: true, locationDetail: 'Ward 3' })} pending />);
    const link = screen.getByRole('link', { name: 'Open task WO-1' });
    expect(link).toHaveAttribute('href', '/tech/w1');
    expect(link).toHaveTextContent('Emergency');
    expect(link).toHaveTextContent('Waiting to send');
    expect(link).toHaveTextContent('Ward 3');
  });
});
