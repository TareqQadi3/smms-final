import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api, centerApi } from '@/lib/api';
import { CENTER, httpError, page, routeGet, signIn } from '@/modules/assets/test-helpers';
import { DepartmentsView } from './DepartmentsView';

vi.mock('@/lib/api', async () => (await import('@/modules/assets/test-mock')).apiMockFactory());

const DEPT = { id: 'd1', name: 'Radiology', nameAr: 'الأشعة', code: 'RAD', active: true, center: CENTER };

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(centerApi.getAll).mockResolvedValue({ data: [CENTER] } as never);
  routeGet({
    '/departments': () => page([DEPT]),
    '/departments/d1/members': () => page([{ id: 'u5', fullName: 'Sara', fullNameAr: 'سارة', role: 'department' }]),
    '/admin/users': () => page([{ id: 'u6', fullName: 'Omar', fullNameAr: 'عمر', username: 'omar', role: 'department', departmentId: null }]),
  });
});

describe('DepartmentsView', () => {
  it('gives read-only roles no write controls but still shows members', async () => {
    signIn('control_room');
    render(<DepartmentsView />);
    expect(await screen.findByText('الأشعة')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'إضافة قسم' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'تعديل' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'تعطيل' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'الأعضاء' }));
    expect(await screen.findByText('سارة')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'إزالة من القسم' })).not.toBeInTheDocument();
  });

  it('denies roles outside the matrix', () => {
    signIn('technician');
    render(<DepartmentsView />);
    expect(screen.getByRole('alert')).toHaveTextContent('غير مصرح');
  });

  it('validates and creates a department', async () => {
    signIn('hospital_supervisor');
    vi.mocked(api.post).mockResolvedValue({ data: DEPT } as never);
    render(<DepartmentsView />);
    await userEvent.click(await screen.findByRole('button', { name: 'إضافة قسم' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: 'حفظ' }));
    expect(await within(dialog).findByText('الرمز مطلوب')).toBeInTheDocument();
    expect(api.post).not.toHaveBeenCalled();
    await userEvent.type(within(dialog).getByLabelText(/الاسم \(عربي\)/), 'الأشعة');
    await userEvent.type(within(dialog).getByLabelText(/الاسم \(إنجليزي\)/), 'Radiology');
    await userEvent.type(within(dialog).getByLabelText(/الرمز/), 'RAD');
    await userEvent.click(within(dialog).getByRole('button', { name: 'حفظ' }));
    await waitFor(() => expect(api.post).toHaveBeenCalledWith('/departments', { centerId: 'c1', name: 'Radiology', nameAr: 'الأشعة', code: 'RAD' }));
    expect(await screen.findByText('تم إنشاء القسم.')).toBeInTheDocument();
  });

  it('shows the server message when a duplicate code is rejected', async () => {
    signIn('system_admin');
    vi.mocked(api.put).mockRejectedValue(httpError(409, 'Department code already exists'));
    render(<DepartmentsView />);
    await userEvent.click(await screen.findByRole('button', { name: 'تعديل' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: 'حفظ' }));
    expect(await within(dialog).findByText('Department code already exists')).toBeInTheDocument();
  });

  it('links and unlinks a user for writers', async () => {
    signIn('hospital_supervisor');
    vi.mocked(api.put).mockResolvedValue({ data: {} } as never);
    render(<DepartmentsView />);
    await userEvent.click(await screen.findByRole('button', { name: 'الأعضاء' }));
    await screen.findByText('سارة');
    await userEvent.selectOptions(await screen.findByLabelText('ربط مستخدم بالقسم', { selector: 'select' }), 'u6');
    await userEvent.click(screen.getByRole('button', { name: 'ربط' }));
    await waitFor(() => expect(api.put).toHaveBeenCalledWith('/admin/users/u6/department', { departmentId: 'd1' }));
    await userEvent.click(screen.getByRole('button', { name: 'إزالة من القسم' }));
    await waitFor(() => expect(api.put).toHaveBeenCalledWith('/admin/users/u5/department', { departmentId: null }));
  });
});
