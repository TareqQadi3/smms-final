import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, centerApi, regionApi } from '@/lib/api';
import { CENTER, httpError, signIn } from '@/modules/assets/test-helpers';
import { filenameFrom } from '../csv';
import { validateRange } from '../validation';
import { ReportsView } from './ReportsView';

vi.mock('@/lib/api', async () => (await import('@/modules/assets/test-mock')).apiMockFactory());

type Cfg = { params?: Record<string, unknown>; responseType?: string };
let handlers: Record<string, (cfg: Cfg) => unknown> = {};

function route() {
  vi.mocked(api.get).mockImplementation(((url: string, cfg: Cfg) => {
    const h = handlers[url];
    if (!h) return Promise.reject(httpError(404));
    try {
      const out = h(cfg);
      return Promise.resolve(out && typeof out === 'object' && 'headers' in out ? out : { data: out });
    } catch (e) {
      return Promise.reject(e);
    }
  }) as never);
}

const VOLUME = { from: '2025-01-01', to: '2025-01-31', groupBy: 'status', total: 7, groups: [{ key: 'completed', label: 'Completed', count: 5 }, { key: 'new', label: 'New', count: 2 }] };

beforeEach(() => {
  vi.clearAllMocks();
  handlers = { '/reports/work-orders/volume': () => VOLUME };
  route();
  vi.mocked(centerApi.getAll).mockResolvedValue({ data: [CENTER] } as never);
  vi.mocked(regionApi.getAll).mockResolvedValue({ data: [{ id: 'r1', name: 'North', nameAr: 'الشمال' }] } as never);
});
afterEach(() => vi.restoreAllMocks());

describe('range validation (pure)', () => {
  it('accepts 366 days and rejects 367, reversed, empty and pre-2000 ranges', () => {
    expect(validateRange('2025-01-01', '2025-12-31')).toBeNull(); // 365 days
    expect(validateRange('2024-01-01', '2024-12-31')).toBeNull(); // 366 days (leap year)
    expect(validateRange('2024-01-01', '2025-01-01')).toBe('rangeTooLong'); // 367 days
    expect(validateRange('2025-02-01', '2025-01-01')).toBe('rangeOrder');
    expect(validateRange('', '2025-01-01')).toBe('rangeRequired');
    expect(validateRange('1999-12-31', '2000-01-02')).toBe('rangeTooEarly');
    expect(validateRange('2025-13-01', '2025-13-02')).toBe('rangeInvalid');
  });
  it('reads the filename from Content-Disposition', () => {
    expect(filenameFrom('attachment; filename="sla-2025-01-01-2025-01-31.csv"', 'x.csv')).toBe('sla-2025-01-01-2025-01-31.csv');
    expect(filenameFrom(undefined, 'x.csv')).toBe('x.csv');
  });
});

describe('ReportsView', () => {
  it('denies roles without report access and gives them no requests', () => {
    signIn('technician');
    render(<ReportsView />);
    expect(screen.getByRole('alert')).toHaveTextContent('غير مصرح');
    expect(api.get).not.toHaveBeenCalled();
  });

  it('loads the volume report with the default 30-day range, as chart and table', async () => {
    signIn('hospital_supervisor');
    render(<ReportsView />);
    expect(await screen.findByRole('list', { name: /عدد البلاغات/ })).toBeInTheDocument();
    expect(screen.getAllByText('مكتمل').length).toBeGreaterThan(0);
    const call = vi.mocked(api.get).mock.calls.find((c) => c[0] === '/reports/work-orders/volume')!;
    const params = (call[1] as Cfg).params!;
    expect(params.groupBy).toBe('status');
    expect(String(params.from)).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(params.centerId).toBeUndefined();
  });

  it('blocks a range longer than 366 days and a reversed range without calling the API again', async () => {
    signIn('system_admin');
    render(<ReportsView />);
    await screen.findByRole('list', { name: /عدد البلاغات/ });
    const calls = vi.mocked(api.get).mock.calls.length;
    fireEvent.change(screen.getByLabelText(/من تاريخ/), { target: { value: '2023-01-01' } });
    fireEvent.change(screen.getByLabelText(/إلى تاريخ/), { target: { value: '2024-06-01' } });
    await userEvent.click(screen.getByRole('button', { name: 'تطبيق' }));
    expect(await screen.findByText('المدة تتجاوز 366 يوماً. قلّص النطاق.')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/إلى تاريخ/), { target: { value: '2022-01-01' } });
    await userEvent.click(screen.getByRole('button', { name: 'تطبيق' }));
    expect(await screen.findByText('تاريخ البداية بعد تاريخ النهاية')).toBeInTheDocument();
    expect(vi.mocked(api.get).mock.calls.length).toBe(calls);
  });

  it('applies a valid range, center and region (SA) to the request', async () => {
    signIn('system_admin');
    vi.mocked(centerApi.getAll).mockResolvedValue({ data: [CENTER, { ...CENTER, id: 'c2', name: 'Two', nameAr: 'الثاني' }] } as never);
    render(<ReportsView />);
    await screen.findByRole('list', { name: /عدد البلاغات/ });
    fireEvent.change(screen.getByLabelText(/من تاريخ/), { target: { value: '2025-01-01' } });
    fireEvent.change(screen.getByLabelText(/إلى تاريخ/), { target: { value: '2025-01-31' } });
    await userEvent.selectOptions(await screen.findByLabelText('المنطقة'), 'r1');
    await userEvent.selectOptions(screen.getByLabelText('المركز'), 'c2');
    await userEvent.click(screen.getByRole('button', { name: 'تطبيق' }));
    await waitFor(() => {
      const last = vi.mocked(api.get).mock.calls.filter((c) => c[0] === '/reports/work-orders/volume').pop()!;
      expect((last[1] as Cfg).params).toMatchObject({ from: '2025-01-01', to: '2025-01-31', centerId: 'c2', groupBy: 'status' });
    });
  });

  it('downloads CSV through an authenticated blob request (never a token in the URL)', async () => {
    signIn('maintenance_manager');
    handlers['/reports/work-orders/volume'] = (cfg) =>
      cfg.params?.format === 'csv'
        ? { data: new Blob(['﻿"a"']), headers: { 'content-disposition': 'attachment; filename="work-order-volume-2025-01-01-2025-01-30.csv"' } }
        : VOLUME;
    const create = vi.fn(() => 'blob:mock');
    const revoke = vi.fn();
    Object.assign(URL, { createObjectURL: create, revokeObjectURL: revoke });
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    render(<ReportsView />);
    await userEvent.click(await screen.findByRole('button', { name: 'تنزيل CSV' }));
    expect(await screen.findByText('تم تنزيل الملف: work-order-volume-2025-01-01-2025-01-30.csv')).toBeInTheDocument();
    const csvCall = vi.mocked(api.get).mock.calls.find((c) => (c[1] as Cfg).params?.format === 'csv')!;
    expect(csvCall[0]).toBe('/reports/work-orders/volume');
    expect(csvCall[1]).toMatchObject({ responseType: 'blob' });
    expect(csvCall[0]).not.toMatch(/token|Bearer/i);
    expect(create).toHaveBeenCalled();
    expect(click).toHaveBeenCalled();
  });

  it('shows the server message when the CSV is refused (422 narrow the range)', async () => {
    signIn('maintenance_manager');
    handlers['/reports/work-orders/volume'] = (cfg) => {
      if (cfg.params?.format === 'csv') {
        throw Object.assign(new Error('x'), { response: { status: 422, data: new Blob([JSON.stringify({ message: 'Report has more than 50000 rows; narrow the date range' })]) } });
      }
      return VOLUME;
    };
    render(<ReportsView />);
    await userEvent.click(await screen.findByRole('button', { name: 'تنزيل CSV' }));
    expect(await screen.findByText('Report has more than 50000 rows; narrow the date range')).toBeInTheDocument();
  });

  it('escalations: available=false is an explained empty state with no CSV button', async () => {
    signIn('hospital_supervisor');
    handlers['/reports/escalations'] = () => ({ from: '2025-01-01', to: '2025-01-31', available: false, groups: [], byDay: [] });
    render(<ReportsView />);
    await userEvent.click(await screen.findByRole('tab', { name: 'التصعيدات' }));
    expect(await screen.findByText(/بيانات التصعيد غير متاحة/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'تنزيل CSV' })).not.toBeInTheDocument();
  });

  it('technicians: name and counts only, no ranking or score', async () => {
    signIn('hospital_supervisor');
    handlers['/reports/technicians'] = () => ({
      content: [{ technician: { id: 't1', fullName: 'Khalid' }, assigned: 9, completed: 6, open: 3, reopened: 1, avgTurnaroundHours: 4.5, completedByPriority: { critical: 1, high: 2, medium: 2, low: 1 }, emergencyHandled: 1 }],
      page: 0, size: 20, totalElements: 1, totalPages: 1,
    });
    render(<ReportsView />);
    await userEvent.click(await screen.findByRole('tab', { name: 'الفنيون' }));
    const table = await screen.findByRole('table');
    expect(within(table).getByText('Khalid')).toBeInTheDocument();
    expect(within(table).getByText('1 / 2 / 2 / 1')).toBeInTheDocument();
    expect(within(table).queryByText(/ترتيب|rank|score|تقييم/i)).not.toBeInTheDocument();
  });

  it('warehouse sees only the inventory tab and no purchasing report', async () => {
    signIn('warehouse');
    handlers['/reports/inventory/stock'] = () => ({ name: 'stock', from: null, to: null, columns: ['center', 'item', 'available'], rows: [['C1', 'Filter', 4]] });
    render(<ReportsView />);
    expect(await screen.findByRole('tab', { name: 'المخزون والمشتريات' })).toBeInTheDocument();
    expect(screen.getAllByRole('tab')).toHaveLength(1);
    expect(await screen.findByText('Filter')).toBeInTheDocument();
    expect(within(screen.getByLabelText('التقرير')).queryByRole('option', { name: 'أوامر الشراء' })).not.toBeInTheDocument();
  });

  it('maps a server 403 to PermissionDenied inside the panel', async () => {
    signIn('hospital_supervisor');
    handlers['/reports/sla'] = () => { throw httpError(403); };
    render(<ReportsView />);
    await userEvent.click(await screen.findByRole('tab', { name: 'اتفاقية الخدمة SLA' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('غير مصرح');
  });

  it('renders English LTR strings', async () => {
    const { useAuthStore } = await import('@/store/authStore');
    useAuthStore.setState({ lang: 'en' });
    signIn('hospital_supervisor');
    render(<ReportsView />);
    expect(await screen.findByRole('heading', { name: 'Reports' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Request volume' })).toBeInTheDocument();
    expect(await screen.findByText('Work orders by Status')).toBeInTheDocument();
  });
});
