import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api, centerApi, controlRoomApi } from '@/lib/api';
import { ASSET, CENTER, httpError, page, routeGet, signIn } from '@/modules/assets/test-helpers';
import { PlanDetailView } from './PlanDetailView';
import { PlanForm } from './PlanForm';
import { PlanListView } from './PlanListView';

vi.mock('@/lib/api', async () => (await import('@/modules/assets/test-mock')).apiMockFactory());
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));

const PLAN = {
  id: 'p1', assetId: 'a1', assetCode: 'AST-001', assetName: 'Chiller', centerId: 'c1', controlRoomId: 'cr1',
  subType: 'hvac', priority: 'medium', title: 'Quarterly service', instructions: 'Check filters', frequencyUnit: 'month',
  frequencyInterval: 3, leadDays: 2, startDate: '2025-01-01', nextDueDate: '2025-04-01', active: true, ownerId: 'u1',
  ownerName: 'Boss', overdue: true, version: 7, lastRuns: [],
};

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(centerApi.getAll).mockResolvedValue({ data: [CENTER] } as never);
  vi.mocked(controlRoomApi.getAll).mockResolvedValue({ data: [{ id: 'cr1', name: 'CR One', nameAr: 'غرفة 1', active: true }] } as never);
});

describe('PlanListView', () => {
  it('lists plans with an overdue badge and sends the overdue filter', async () => {
    signIn('maintenance_manager');
    routeGet({ '/maintenance-plans': () => page([PLAN]) });
    render(<PlanListView />);
    expect(await screen.findByRole('link', { name: 'Quarterly service' })).toHaveAttribute('href', '/maintenance-plans/p1');
    expect(screen.getByText('متأخرة', { selector: 'span' })).toBeInTheDocument();
    await userEvent.click(screen.getByLabelText('المتأخرة فقط'));
    await userEvent.click(screen.getByRole('button', { name: 'تصفية' }));
    await waitFor(() => {
      const last = vi.mocked(api.get).mock.calls.filter((c) => c[0] === '/maintenance-plans').pop()!;
      expect((last[1] as { params: Record<string, unknown> }).params).toMatchObject({ overdue: true });
    });
  });

  it('denies roles without plan access', () => {
    signIn('technician');
    render(<PlanListView />);
    expect(screen.getByRole('alert')).toHaveTextContent('غير مصرح');
  });

  it('shows "add" only for writers', async () => {
    signIn('control_room');
    routeGet({ '/maintenance-plans': () => page([]) });
    render(<PlanListView />);
    expect(await screen.findByText('لا توجد خطط')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'إضافة خطة' })).not.toBeInTheDocument();
  });
});

describe('PlanForm', () => {
  it('validates interval, lead days, control room and start date before calling the API', async () => {
    signIn('hospital_supervisor');
    routeGet({ '/assets': () => page([ASSET]) });
    render(<PlanForm mode="create" onSaved={() => {}} onCancel={() => {}} />);
    fireEvent.change(screen.getByLabelText(/الفاصل/), { target: { value: '0' } });
    fireEvent.change(screen.getByLabelText(/أيام الإنشاء المسبق/), { target: { value: '61' } });
    await userEvent.click(screen.getByRole('button', { name: 'حفظ' }));
    expect(await screen.findByText('اختر الأصل')).toBeInTheDocument();
    expect(screen.getByText('العنوان مطلوب')).toBeInTheDocument();
    expect(screen.getAllByText('اختر غرفة التحكم')).toHaveLength(2); // select placeholder + error
    expect(screen.getByText('الفاصل رقم صحيح بين 1 و 366')).toBeInTheDocument();
    expect(screen.getByText('أيام الإنشاء المسبق رقم صحيح بين 0 و 60')).toBeInTheDocument();
    expect(screen.getByText('تاريخ البدء مطلوب')).toBeInTheDocument();
    expect(api.post).not.toHaveBeenCalled();
  });

  it('creates a plan: asset pick loads the control rooms of its center', async () => {
    signIn('hospital_supervisor');
    routeGet({ '/assets': () => page([ASSET]) });
    vi.mocked(api.post).mockResolvedValue({ data: PLAN } as never);
    const onSaved = vi.fn();
    render(<PlanForm mode="create" onSaved={onSaved} onCancel={() => {}} />);
    await userEvent.click(screen.getByPlaceholderText('ابحث عن أصل بالاسم أو الرمز'));
    await userEvent.click(await within(await screen.findByRole('listbox')).findByRole('option'));
    await waitFor(() => expect(controlRoomApi.getAll).toHaveBeenCalledWith('c1'));
    await userEvent.selectOptions(await screen.findByLabelText(/غرفة التحكم/), 'cr1');
    await userEvent.type(screen.getByLabelText(/العنوان/), 'Quarterly service');
    fireEvent.change(screen.getByLabelText(/تاريخ البدء/), { target: { value: '2030-01-01' } });
    await userEvent.click(screen.getByRole('button', { name: 'حفظ' }));
    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(vi.mocked(api.post).mock.calls[0]).toEqual([
      '/maintenance-plans',
      expect.objectContaining({ assetId: 'a1', controlRoomId: 'cr1', frequencyInterval: 1, startDate: '2030-01-01', title: 'Quarterly service' }),
    ]);
  });

  it('edit: 409 asks to reload and then sends the fresh version', async () => {
    signIn('maintenance_manager');
    routeGet({ '/maintenance-plans/p1': () => ({ ...PLAN, version: 8, title: 'Renamed' }) });
    vi.mocked(api.put).mockRejectedValueOnce(httpError(409));
    render(<PlanForm mode="edit" plan={PLAN as never} onSaved={() => {}} onCancel={() => {}} />);
    await userEvent.click(screen.getByRole('button', { name: 'حفظ' }));
    expect(await screen.findByText(/تم تعديل هذه الخطة من مستخدم آخر/)).toBeInTheDocument();
    expect(vi.mocked(api.put).mock.calls[0][1]).toMatchObject({ version: 7 });
    await userEvent.click(screen.getByRole('button', { name: 'إعادة تحميل' }));
    expect(await screen.findByDisplayValue('Renamed')).toBeInTheDocument();
    vi.mocked(api.put).mockResolvedValueOnce({ data: PLAN } as never);
    await userEvent.click(screen.getByRole('button', { name: 'حفظ' }));
    await waitFor(() => expect(vi.mocked(api.put).mock.calls[1][1]).toMatchObject({ version: 8 }));
  });
});

describe('PlanDetailView', () => {
  const runs = { id: 'r1', dueDate: '2025-04-01', status: 'generated', source: 'scheduler', workOrderId: 'w1', error: null, overdueNotifiedAt: null, createdAt: '2025-03-29T00:00:00Z' };

  it('shows run history and generate-now; a 409 shows the server message', async () => {
    signIn('hospital_supervisor');
    routeGet({ '/maintenance-plans/p1': () => PLAN, '/maintenance-plans/p1/runs': () => page([runs]) });
    vi.mocked(api.post).mockRejectedValueOnce(httpError(409, 'Run already generated'));
    render(<PlanDetailView id="p1" />);
    expect(await screen.findByRole('heading', { name: 'Quarterly service' })).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: 'فتح البلاغ' })).toHaveAttribute('href', '/work-orders/w1');
    await userEvent.click(screen.getByRole('button', { name: 'إنشاء الآن' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: 'إنشاء الآن' }));
    expect(await screen.findByText('Run already generated')).toBeInTheDocument();
    expect(api.post).toHaveBeenCalledWith('/maintenance-plans/p1/generate-now');
  });

  it('deactivates after confirmation; read-only roles get no actions', async () => {
    signIn('hospital_supervisor');
    routeGet({ '/maintenance-plans/p1': () => PLAN, '/maintenance-plans/p1/runs': () => page([]) });
    vi.mocked(api.post).mockResolvedValue({ data: { ...PLAN, active: false } } as never);
    const { unmount } = render(<PlanDetailView id="p1" />);
    await userEvent.click(await screen.findByRole('button', { name: 'تعطيل' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: 'تعطيل' }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/maintenance-plans/p1/deactivate'));
    unmount();

    signIn('operating_company_manager');
    render(<PlanDetailView id="p1" />);
    await screen.findByRole('heading', { name: 'Quarterly service' });
    expect(screen.queryByRole('button', { name: 'تعديل' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'إنشاء الآن' })).not.toBeInTheDocument();
  });
});
