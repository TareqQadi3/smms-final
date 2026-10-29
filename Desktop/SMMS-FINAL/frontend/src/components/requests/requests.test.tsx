import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AxiosError } from 'axios';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { RequestWorkOrder } from '@/modules/requests/types';

vi.mock('@/modules/requests/api', () => ({
  requestsApi: {
    confirm: vi.fn(), infoRequests: vi.fn(), provideInfo: vi.fn(), getRating: vi.fn(), rate: vi.fn(), createStatusLink: vi.fn(),
  },
  publicStatusApi: { get: vi.fn() },
}));
import { publicStatusApi, requestsApi } from '@/modules/requests/api';
import { ConfirmationPanel, InfoRequestPanel, RatingPanel, StatusLinkPanel } from './Panels';
import { PublicStatusView } from './PublicStatusView';

const api = vi.mocked(requestsApi);
const wo = (o: Partial<RequestWorkOrder> = {}): RequestWorkOrder => ({
  id: 'w1', ticketNumber: 'WO-1', status: 'completed', priority: 'low', title: 'T', createdAt: '2026-01-01T00:00:00Z', techReport: 'Done it', ...o,
});
const http = (status: number, message = 'm') => {
  const config = { headers: {} } as never;
  return new AxiosError('x', 'E', config, null, { status, data: { message }, statusText: '', headers: {}, config });
};
const ok = <T,>(data: T) => ({ data }) as never;

beforeEach(() => { vi.clearAllMocks(); });

describe('ConfirmationPanel', () => {
  it('accept sends an Idempotency-Key; the same key is reused after a network failure', async () => {
    api.confirm.mockRejectedValueOnce(new AxiosError('Network Error', 'ERR_NETWORK')).mockResolvedValueOnce(ok({}));
    const onChanged = vi.fn();
    render(<ConfirmationPanel wo={wo()} onChanged={onChanged} />);
    expect(screen.getByText('Done it')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'نعم، تم الحل' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('لا يوجد اتصال');
    await userEvent.click(screen.getByRole('button', { name: 'نعم، تم الحل' }));
    await waitFor(() => expect(onChanged).toHaveBeenCalled());
    const [k1, k2] = api.confirm.mock.calls.map((c) => c[3]);
    expect(k1).toBeTruthy();
    expect(k1).toBe(k2);
  });

  it('dispute requires a note', async () => {
    api.confirm.mockResolvedValue(ok({}));
    render(<ConfirmationPanel wo={wo()} onChanged={() => {}} />);
    await userEvent.click(screen.getByRole('button', { name: 'لم تُحل المشكلة' }));
    await userEvent.click(await screen.findByRole('button', { name: 'إرسال' }));
    expect(await screen.findByText('الملاحظة مطلوبة عند رفض الإنجاز.')).toBeInTheDocument();
    expect(api.confirm).not.toHaveBeenCalled();
    await userEvent.type(screen.getByRole('dialog').querySelector('textarea')!, 'Still leaking');
    await userEvent.click(screen.getByRole('button', { name: 'إرسال' }));
    await waitFor(() => expect(api.confirm).toHaveBeenCalledWith('w1', false, 'Still leaking', expect.any(String)));
  });

  it('shows the recorded decision instead of buttons, and a 422 maps to its message', async () => {
    const { unmount } = render(<ConfirmationPanel wo={wo({ requesterConfirmation: 'accepted', requesterConfirmedAt: '2026-01-02T00:00:00Z' })} onChanged={() => {}} />);
    expect(screen.queryByRole('button')).toBeNull();
    unmount();
    api.confirm.mockRejectedValue(http(422, 'Only a completed ticket can be confirmed'));
    render(<ConfirmationPanel wo={wo()} onChanged={() => {}} />);
    await userEvent.click(screen.getByRole('button', { name: 'نعم، تم الحل' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Only a completed ticket');
  });
});

describe('InfoRequestPanel', () => {
  it('answers the open question', async () => {
    api.infoRequests.mockResolvedValue(ok([{ id: 'i1', question: 'Which floor?', requestedAt: '2026-01-01T00:00:00Z' }]));
    api.provideInfo.mockResolvedValue(ok({}));
    render(<InfoRequestPanel wo={wo({ status: 'under_review', currentStage: 'info_requested' })} active onChanged={() => {}} />);
    expect(await screen.findByText('Which floor?')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'إرسال الرد' }));
    expect(await screen.findByText('اكتب ردك أولا.')).toBeInTheDocument();
    await userEvent.type(screen.getByRole('textbox'), 'Third');
    await userEvent.click(screen.getByRole('button', { name: 'إرسال الرد' }));
    await waitFor(() => expect(api.provideInfo).toHaveBeenCalledWith('w1', 'Third'));
  });
});

describe('RatingPanel', () => {
  it('404 means not rated: pick stars and submit', async () => {
    api.getRating.mockRejectedValue(http(404));
    api.rate.mockResolvedValue(ok({ id: 'r', score: 4, createdAt: '2026-01-02T00:00:00Z' }));
    render(<RatingPanel wo={wo({ status: 'closed' })} />);
    await userEvent.click(await screen.findByRole('button', { name: 'إرسال التقييم' }));
    expect(await screen.findByText('اختر عدد النجوم.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('radio', { name: '4 من 5' }));
    await userEvent.click(screen.getByRole('button', { name: 'إرسال التقييم' }));
    await waitFor(() => expect(api.rate).toHaveBeenCalledWith('w1', 4, undefined));
    expect(await screen.findByText('شكرا لتقييمك.')).toBeInTheDocument();
  });

  it('shows an existing rating read-only', async () => {
    api.getRating.mockResolvedValue(ok({ id: 'r', score: 5, comment: 'great', createdAt: '2026-01-02T00:00:00Z' }));
    render(<RatingPanel wo={wo({ status: 'closed' })} />);
    expect(await screen.findByRole('img', { name: 'تقييمك: 5 من 5' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'إرسال التقييم' })).toBeNull();
  });
});

describe('StatusLinkPanel', () => {
  it('creates a link, shows the absolute url once and asks before replacing it', async () => {
    api.createStatusLink.mockResolvedValue(ok({ token: 'tok', path: '/status/tok', expiresAt: '2026-02-01T00:00:00Z' }));
    render(<StatusLinkPanel wo={wo()} />);
    await userEvent.click(screen.getByRole('button', { name: 'إنشاء رابط متابعة' }));
    const input = (await screen.findByRole('textbox', { name: 'رابط المتابعة' })) as HTMLInputElement;
    expect(input.value).toBe(`${window.location.origin}/status/tok`);
    await userEvent.click(screen.getByRole('button', { name: 'إنشاء رابط جديد' }));
    expect(await screen.findByRole('dialog')).toHaveTextContent('سيتوقف الرابط السابق');
    expect(api.createStatusLink).toHaveBeenCalledTimes(1);
  });

  it('feature disabled on the server gives a friendly message', async () => {
    api.createStatusLink.mockRejectedValue(http(422, 'Public status links are disabled'));
    render(<StatusLinkPanel wo={wo()} />);
    await userEvent.click(screen.getByRole('button', { name: 'إنشاء رابط متابعة' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('غير مفعلة');
  });
});

describe('PublicStatusView', () => {
  it('shows only the whitelisted fields', async () => {
    vi.mocked(publicStatusApi.get).mockResolvedValue(ok({ ticketNumber: 'WO-9', status: 'in_progress', statusGroup: 'in_progress', createdAt: '2026-01-01T00:00:00Z', lastUpdatedAt: '2026-01-02T00:00:00Z', completed: false }));
    render(<PublicStatusView token="abc" />);
    const card = await screen.findByTestId('public-status');
    expect(card).toHaveTextContent('WO-9');
    expect(card).toHaveTextContent('قيد التنفيذ');
    expect(publicStatusApi.get).toHaveBeenCalledWith('abc');
  });

  it('404 (unknown, expired, revoked or disabled) is one neutral message; 429 asks to wait', async () => {
    vi.mocked(publicStatusApi.get).mockRejectedValue(http(404));
    const { unmount } = render(<PublicStatusView token="bad" />);
    expect(await screen.findByRole('alert')).toHaveTextContent('الرابط غير صالح أو انتهت صلاحيته');
    unmount();
    vi.mocked(publicStatusApi.get).mockRejectedValue(http(429));
    render(<PublicStatusView token="bad" />);
    expect(await screen.findByRole('alert')).toHaveTextContent('محاولات كثيرة');
  });
});
