import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useAuthStore } from '@/store/authStore';
import { NotificationPreferencesForm } from './NotificationPreferencesForm';
import { DeliveriesTable, ProviderStatusCard } from './DeliveriesTable';

const getPrefs = vi.fn();
const putPrefs = vi.fn();
const deliveries = vi.fn();
const providers = vi.fn();
vi.mock('@/modules/workflow/api', async (orig) => ({
  ...(await orig<typeof import('@/modules/workflow/api')>()),
  notificationPrefsApi: { get: (...a: unknown[]) => getPrefs(...a), put: (...a: unknown[]) => putPrefs(...a) },
  adminNotificationApi: { deliveries: (...a: unknown[]) => deliveries(...a), providers: (...a: unknown[]) => providers(...a) },
}));

beforeEach(() => { [getPrefs, putPrefs, deliveries, providers].forEach((m) => m.mockReset()); act(() => useAuthStore.setState({ lang: 'en' })); });

describe('NotificationPreferencesForm', () => {
  it('is honest when providers are not configured, and saves the choice', async () => {
    getPrefs.mockResolvedValue({ data: { emailEnabled: false, smsEnabled: false, types: null, emailAvailable: false, smsAvailable: true } });
    putPrefs.mockResolvedValue({ data: { emailEnabled: true, smsEnabled: false, types: null, emailAvailable: false, smsAvailable: true } });
    render(<NotificationPreferencesForm />);
    expect(await screen.findByText(/email provider is not configured/i)).toBeInTheDocument();
    expect(screen.getByText('SMS provider ready')).toBeInTheDocument();
    const save = screen.getByRole('button', { name: 'Save settings' });
    expect(save).toBeDisabled();
    await userEvent.click(screen.getByLabelText('Receive notifications by Email'));
    await userEvent.click(save);
    await waitFor(() => expect(putPrefs).toHaveBeenCalledWith({ emailEnabled: true, smsEnabled: false, types: null }));
    expect(await screen.findByText('Settings saved')).toBeInTheDocument();
  });
  it('shows the server explanation for a 422 (no address) and an error state on load failure', async () => {
    getPrefs.mockResolvedValueOnce({ data: { emailEnabled: false, smsEnabled: false, emailAvailable: true, smsAvailable: true } });
    putPrefs.mockRejectedValue({ response: { status: 422, data: { message: 'Your account has no phone number for SMS' } } });
    const { unmount } = render(<NotificationPreferencesForm />);
    await userEvent.click(await screen.findByLabelText('Receive notifications by SMS'));
    await userEvent.click(screen.getByRole('button', { name: 'Save settings' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('no phone number');
    unmount();
    getPrefs.mockRejectedValueOnce({ response: { status: 500 } });
    render(<NotificationPreferencesForm />);
    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });
});

const page = (content: object[], over: object = {}) => ({ data: { content, page: 0, size: 20, totalElements: content.length, totalPages: 1, ...over } });
const row = (o: object = {}) => ({ id: 'd1', channel: 'email', destinationMasked: 'a***@x.com', status: 'not_configured', attempts: 0, provider: 'smtp', createdAt: '2026-01-01T10:00:00Z', lastError: null, ...o });

describe('DeliveriesTable', () => {
  it('lists rows with masked destinations and localized status', async () => {
    deliveries.mockResolvedValue(page([row(), row({ id: 'd2', channel: 'sms', status: 'sent', destinationMasked: '+966****12' })]));
    render(<DeliveriesTable />);
    expect(await screen.findByText('a***@x.com')).toBeInTheDocument();
    const table = within(screen.getByRole('table'));
    expect(table.getByText('Provider not configured')).toBeInTheDocument();
    expect(table.getByText('Accepted by provider')).toBeInTheDocument();
    expect(deliveries).toHaveBeenCalledWith({ page: 0, size: 20 });
  });
  it('applies filters (and resets the page) and paginates zero-based', async () => {
    deliveries.mockResolvedValue(page([row()], { totalElements: 45, totalPages: 3 }));
    render(<DeliveriesTable />);
    await screen.findByText('a***@x.com');
    await userEvent.selectOptions(screen.getByLabelText('Status'), 'dead');
    await userEvent.selectOptions(screen.getByLabelText('Channel'), 'sms');
    await userEvent.click(screen.getByRole('button', { name: 'Apply' }));
    await waitFor(() => expect(deliveries).toHaveBeenLastCalledWith({ status: 'dead', channel: 'sms', from: '', to: '', page: 0, size: 20 }));
    await userEvent.click(screen.getByRole('button', { name: /next/i }));
    await waitFor(() => expect(deliveries).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'dead', page: 1 })));
  });
  it('rejects an inverted date range without calling the API again', async () => {
    deliveries.mockResolvedValue(page([row()]));
    render(<DeliveriesTable />);
    await screen.findByText('a***@x.com');
    deliveries.mockClear();
    await userEvent.type(screen.getByLabelText('From date'), '2026-03-10');
    await userEvent.type(screen.getByLabelText('To date'), '2026-03-01');
    await userEvent.click(screen.getByRole('button', { name: 'Apply' }));
    expect(await screen.findByText('The start date is after the end date')).toBeInTheDocument();
    expect(deliveries).not.toHaveBeenCalled();
  });
  it('empty and error states', async () => {
    deliveries.mockResolvedValueOnce(page([]));
    const { unmount } = render(<DeliveriesTable />);
    expect(await screen.findByText('No matching deliveries')).toBeInTheDocument();
    unmount();
    deliveries.mockRejectedValueOnce({ response: { status: 403 } });
    render(<DeliveriesTable />);
    expect(await screen.findByRole('alert')).toHaveTextContent('permission');
  });
});

describe('ProviderStatusCard', () => {
  it('shows enabled/configured flags and problems without secrets', async () => {
    providers.mockResolvedValue({ data: [
      { channel: 'email', provider: 'smtp', enabled: true, configured: false, problems: ['SMTP host is missing'] },
      { channel: 'sms', provider: 'none', enabled: false, configured: false, problems: [] },
    ] });
    render(<ProviderStatusCard />);
    expect(await screen.findByText('SMTP host is missing')).toBeInTheDocument();
    expect(screen.getAllByText('Not configured').length).toBe(2);
  });
});
