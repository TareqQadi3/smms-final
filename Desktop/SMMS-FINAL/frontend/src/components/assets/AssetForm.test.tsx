import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api, centerApi } from '@/lib/api';
import { ASSET, CENTER, httpError, page, routeGet, signIn } from '@/modules/assets/test-helpers';
import { AssetForm } from './AssetForm';

vi.mock('@/lib/api', async () => (await import('@/modules/assets/test-mock')).apiMockFactory());

beforeEach(() => {
  vi.clearAllMocks();
  signIn('hospital_supervisor');
  vi.mocked(centerApi.getAll).mockResolvedValue({ data: [CENTER] } as never);
  routeGet({ '/departments': () => page([]), '/assets/a1': () => ({ ...ASSET, name: 'Fresh name', version: 4 }) });
});

describe('AssetForm', () => {
  it('shows required-field errors and never calls the API', async () => {
    const onSaved = vi.fn();
    render(<AssetForm mode="create" onSaved={onSaved} onCancel={() => {}} />);
    await userEvent.click(screen.getByRole('button', { name: 'حفظ' }));
    expect(await screen.findByText('الاسم الإنجليزي مطلوب')).toBeInTheDocument();
    expect(screen.getByText('الاسم العربي مطلوب')).toBeInTheDocument();
    expect(api.post).not.toHaveBeenCalled();
    expect(onSaved).not.toHaveBeenCalled();
  });

  it('rejects a future install date and a warranty that ends before the install date', async () => {
    render(<AssetForm mode="create" onSaved={() => {}} onCancel={() => {}} />);
    await userEvent.type(screen.getByLabelText(/الاسم \(عربي\)/), 'مبرد');
    await userEvent.type(screen.getByLabelText(/الاسم \(إنجليزي\)/), 'Chiller');
    fireEvent.change(screen.getByLabelText('تاريخ التركيب'), { target: { value: '2999-01-01' } });
    await userEvent.click(screen.getByRole('button', { name: 'حفظ' }));
    expect(await screen.findByText('تاريخ التركيب لا يمكن أن يكون في المستقبل')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('تاريخ التركيب'), { target: { value: '2024-05-01' } });
    fireEvent.change(screen.getByLabelText('انتهاء الضمان'), { target: { value: '2024-01-01' } });
    await userEvent.click(screen.getByRole('button', { name: 'حفظ' }));
    expect(await screen.findByText('تاريخ انتهاء الضمان يجب ألا يسبق تاريخ التركيب')).toBeInTheDocument();
    expect(api.post).not.toHaveBeenCalled();
  });

  it('creates an asset with a trimmed payload (single center is preselected)', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: { ...ASSET, id: 'new1' } } as never);
    const onSaved = vi.fn();
    render(<AssetForm mode="create" onSaved={onSaved} onCancel={() => {}} />);
    await waitFor(() => expect((screen.getByLabelText(/المركز/) as HTMLSelectElement).value).toBe('c1'));
    await userEvent.type(screen.getByLabelText(/الاسم \(عربي\)/), '  مبرد ');
    await userEvent.type(screen.getByLabelText(/الاسم \(إنجليزي\)/), 'Chiller ');
    await userEvent.click(screen.getByRole('button', { name: 'حفظ' }));
    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    const [url, body] = vi.mocked(api.post).mock.calls[0] as [string, Record<string, unknown>];
    expect(url).toBe('/assets');
    expect(body).toMatchObject({ centerId: 'c1', name: 'Chiller', nameAr: 'مبرد', assetCode: null, status: 'active' });
  });

  it('edit sends the loaded version; a 409 asks to reload and reload adopts the new version', async () => {
    vi.mocked(api.put).mockRejectedValueOnce(httpError(409, 'The record was modified concurrently, reload and try again'));
    render(<AssetForm mode="edit" asset={ASSET as never} onSaved={() => {}} onCancel={() => {}} />);
    await userEvent.click(screen.getByRole('button', { name: 'حفظ' }));
    expect(await screen.findByText(/تم تعديل هذا الأصل من مستخدم آخر/)).toBeInTheDocument();
    expect(vi.mocked(api.put).mock.calls[0][1]).toMatchObject({ version: 3 });

    await userEvent.click(screen.getByRole('button', { name: 'إعادة تحميل' }));
    expect(await screen.findByText('تم تحميل أحدث نسخة.')).toBeInTheDocument();
    expect(screen.getByLabelText(/الاسم \(إنجليزي\)/)).toHaveValue('Fresh name');

    vi.mocked(api.put).mockResolvedValueOnce({ data: { ...ASSET, version: 5 } } as never);
    await userEvent.click(screen.getByRole('button', { name: 'حفظ' }));
    await waitFor(() => expect(api.put).toHaveBeenCalledTimes(2));
    expect(vi.mocked(api.put).mock.calls[1][1]).toMatchObject({ version: 4, name: 'Fresh name' });
  });

  it('renders in English with LTR labels', async () => {
    const { useAuthStore } = await import('@/store/authStore');
    useAuthStore.setState({ lang: 'en' });
    render(<AssetForm mode="create" onSaved={() => {}} onCancel={() => {}} />);
    expect(screen.getByLabelText(/Name \(English\)/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save' })).toBeInTheDocument();
  });
});
