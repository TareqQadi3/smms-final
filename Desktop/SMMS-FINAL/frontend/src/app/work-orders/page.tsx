'use client';

import { useEffect, useState, useCallback } from 'react';
import DashboardLayout from '@/components/layout/DashboardLayout';
import { useAuthStore } from '@/store/authStore';
import { useT, statusColors, priorityColors, tStatus, tPriority } from '@/lib/i18n';
import { workOrderApi, controlRoomApi } from '@/lib/api';
import { WorkOrder, TicketStatus, TicketPriority } from '@/lib/types';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Plus, Search, RefreshCw, Eye, ChevronLeft, ChevronRight } from 'lucide-react';
import { formatDate } from '@/lib/utils';
import { StateMessage } from '@/components/ui/state-message';
import Link from 'next/link';

const STATUSES: TicketStatus[] = ['new','under_review','assigned','in_progress','waiting_materials','waiting_approval','completed','closed','cancelled'];
const PRIORITIES: TicketPriority[] = ['critical','high','medium','low'];
const SUB_TYPES = ['electrical','plumbing','hvac','civil','printer','network','software','devices','equipment_failure','preventive_maintenance','calibration'];

export default function WorkOrdersPage() {
  const { lang, user } = useAuthStore();
  const tr = useT(lang);
  const isAr = lang === 'ar';

  const [orders, setOrders] = useState<WorkOrder[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);

  // filters
  const [search, setSearch] = useState('');
  const [filterStatus, setFilterStatus] = useState('');
  const [filterPriority, setFilterPriority] = useState('');

  // create dialog
  const [createOpen, setCreateOpen] = useState(false);
  const [controlRooms, setControlRooms] = useState<{id:string;name:string}[]>([]);
  const [form, setForm] = useState({ controlRoomId:'', subType:'', title:'', description:'', locationDetail:'', priority:'medium' as TicketPriority });
  const [submitting, setSubmitting] = useState(false);
  // Non-blocking duplicate hint returned by the create call (only tickets the user may already see)
  const [dupes, setDupes] = useState<{ id: string; ticketNumber: string }[]>([]);

  const canCreate = user && ['resident','department','control_room','system_admin'].includes(user.role);

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError(false);
    try {
      const params: Record<string,unknown> = { page, size: 10 };
      if (filterStatus) params.status = filterStatus;
      if (filterPriority) params.priority = filterPriority;
      const res = await workOrderApi.getAll(params);
      setOrders(res.data.content || []);
      setTotal(res.data.totalElements || 0);
    } catch { setLoadError(true); }
    finally { setLoading(false); }
  }, [page, filterStatus, filterPriority]);

  useEffect(() => { load(); }, [load]);

  const openCreate = async () => {
    try {
      const res = await controlRoomApi.getAll();
      setControlRooms(res.data || []);
    } catch { /* silent */ }
    setCreateOpen(true);
  };

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    try {
      const res = await workOrderApi.create(form);
      setDupes(Array.isArray(res.data?.possibleDuplicates) ? res.data.possibleDuplicates : []);
      setCreateOpen(false);
      setForm({ controlRoomId:'', subType:'', title:'', description:'', locationDetail:'', priority:'medium' });
      load();
    } catch { /* silent */ }
    finally { setSubmitting(false); }
  };

  const statusLabel: Record<string, string> = {
    new: isAr ? 'جديد' : 'New',
    under_review: isAr ? 'مراجعة' : 'Under Review',
    assigned: isAr ? 'مُعيَّن' : 'Assigned',
    in_progress: isAr ? 'تنفيذ' : 'In Progress',
    waiting_materials: isAr ? 'انتظار مواد' : 'Waiting Materials',
    waiting_approval: isAr ? 'انتظار موافقة' : 'Waiting Approval',
    completed: isAr ? 'مكتمل' : 'Completed',
    closed: isAr ? 'مغلق' : 'Closed',
    cancelled: isAr ? 'ملغي' : 'Cancelled',
  };

  return (
    <DashboardLayout>
      <div className="max-w-7xl mx-auto space-y-5">
        {/* Header */}
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold text-foreground">{tr.workOrders}</h1>
            <p className="text-sm text-muted-foreground">{total} {isAr ? 'بلاغ' : 'tickets'}</p>
          </div>
          {canCreate && (
            <Button onClick={openCreate} className="gap-2 bg-[#0f2744] hover:bg-[#1a3a6b]">
              <Plus size={16} />
              {tr.createWorkOrder}
            </Button>
          )}
        </div>

        {dupes.length > 0 && (
          <div className="rounded-md border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900 flex items-start justify-between gap-3">
            <p>
              {isAr ? 'تم إنشاء البلاغ. قد يتشابه مع بلاغات مفتوحة: ' : 'Ticket created. It may duplicate open tickets: '}
              {dupes.map(d => d.ticketNumber).join(', ')}
            </p>
            <button type="button" onClick={() => setDupes([])} className="text-xs underline shrink-0">
              {isAr ? 'إخفاء' : 'Dismiss'}
            </button>
          </div>
        )}

        {/* Filters */}
        <Card className="border-0 shadow-sm">
          <CardContent className="p-4">
            <div className="flex flex-wrap gap-3 items-center">
              <div className="relative flex-1 min-w-[200px]">
                <Search size={15} className={`absolute top-1/2 -translate-y-1/2 text-gray-400 start-3`} />
                <Input
                  placeholder={tr.search}
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  className={`ps-9 h-9`}
                />
              </div>
              <Select value={filterStatus} onValueChange={(v) => { setFilterStatus(!v || v === 'all' ? '' : v); setPage(0); }}>
                <SelectTrigger className="w-40 h-9">
                  <SelectValue placeholder={tr.status} />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="all">{tr.all}</SelectItem>
                  {STATUSES.map(s => <SelectItem key={s} value={s}>{statusLabel[s]}</SelectItem>)}
                </SelectContent>
              </Select>
              <Select value={filterPriority} onValueChange={(v) => { setFilterPriority(!v || v === 'all' ? '' : v); setPage(0); }}>
                <SelectTrigger className="w-36 h-9">
                  <SelectValue placeholder={tr.priority} />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="all">{tr.all}</SelectItem>
                  {PRIORITIES.map(p => <SelectItem key={p} value={p} className="capitalize">{p}</SelectItem>)}
                </SelectContent>
              </Select>
              <Button variant="outline" size="sm" onClick={load} className="gap-1.5 h-9">
                <RefreshCw size={14} />
                {isAr ? 'تحديث' : 'Refresh'}
              </Button>
            </div>
          </CardContent>
        </Card>

        {/* Table */}
        <Card className="border-0 shadow-sm">
          <CardContent className="p-0">
            {loading ? (
              <div className="flex items-center justify-center h-48">
                <div className="w-8 h-8 border-4 border-blue-600 border-t-transparent rounded-full animate-spin" />
              </div>
            ) : loadError ? (
              <StateMessage kind="error" onRetry={load} retryLabel={isAr ? 'إعادة المحاولة' : 'Try again'}>
                {isAr ? 'تعذّر تحميل البلاغات. تحقق من الاتصال ثم أعد المحاولة.' : 'Unable to load tickets. Check your connection and try again.'}
              </StateMessage>
            ) : orders.length === 0 ? (
              <StateMessage>
                {filterStatus || filterPriority
                  ? (isAr ? 'لا توجد بلاغات تطابق التصفية. غيّر الحالة أو الأولوية.' : 'No tickets match the filters. Change the status or priority.')
                  : (isAr ? 'لا توجد بلاغات بعد.' : 'No tickets yet.')}
              </StateMessage>
            ) : (
              <div className="table-scroll">
                <table className="w-full text-sm">
                  <thead className="bg-muted/50 border-b border-border">
                    <tr>
                      {[tr.ticketNumber, tr.title, tr.status, tr.priority, tr.assignedTo, tr.date, tr.actions].map((h, i) => (
                        <th key={h} className={`text-start px-4 py-3 font-medium text-gray-600 ${i === 2 ? 'max-md:hidden' : ''}`}>{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {orders
                      .filter(o => !search || o.title?.toLowerCase().includes(search.toLowerCase()) || o.ticketNumber?.includes(search))
                      .map((wo) => (
                      <tr key={wo.id} className="border-b last:border-0 hover:bg-muted/30 transition-colors">
                        <td className="px-4 py-3 font-mono text-xs text-blue-600 font-semibold whitespace-nowrap tabular-nums"><bdi dir="ltr">{wo.ticketNumber}</bdi>
                          <br /><span className={`md:hidden mt-1.5 inline-block px-2 py-0.5 rounded-full text-xs font-medium ${statusColors[wo.status] || 'bg-gray-100'}`}>{tStatus(wo.status, lang)}</span></td>
                        <td className="px-4 py-3 max-w-[200px]">
                          <p className="truncate font-medium">{wo.title}</p>
                          {wo.slaBreached && (
                            <span className="text-xs text-red-600 font-medium">{isAr ? '⚠ تجاوز SLA' : '⚠ SLA Breached'}</span>
                          )}
                        </td>
                        <td className="px-4 py-3 whitespace-nowrap max-md:hidden">
                          <span className={`px-2.5 py-1 rounded-full text-xs font-medium ${statusColors[wo.status] || 'bg-gray-100'}`}>
                            {tStatus(wo.status, lang)}
                          </span>
                        </td>
                        <td className="px-4 py-3">
                          <span className={`px-2 py-0.5 rounded text-xs font-medium border ${priorityColors[wo.priority] || ''}`}>
                            {tPriority(wo.priority, lang)}
                          </span>
                        </td>
                        <td className="px-4 py-3 text-gray-600">{wo.assignedTo?.fullName || '-'}</td>
                        <td className="px-4 py-3 text-muted-foreground text-xs whitespace-nowrap tabular-nums">
                          {formatDate(wo.createdAt, lang)}
                        </td>
                        <td className="px-4 py-3">
                          <Link href={`/work-orders/${wo.id}`} className="inline-flex">
                            <Button variant="ghost" size="sm" className="gap-1 text-xs">
                              <Eye size={13} /> {isAr ? 'عرض' : 'View'}
                            </Button>
                          </Link>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            {/* Pagination */}
            {total > 10 && (
              <div className="flex items-center justify-between px-4 py-3 border-t">
                <p className="text-sm text-muted-foreground">
                  {isAr ? `عرض ${page * 10 + 1} - ${Math.min((page + 1) * 10, total)} من ${total}` : `Showing ${page * 10 + 1}-${Math.min((page + 1) * 10, total)} of ${total}`}
                </p>
                <div className="flex gap-2">
                  <Button variant="outline" size="sm" onClick={() => setPage(p => p - 1)} disabled={page === 0}>
                    {isAr ? <ChevronRight size={16} /> : <ChevronLeft size={16} />}
                  </Button>
                  <Button variant="outline" size="sm" onClick={() => setPage(p => p + 1)} disabled={(page + 1) * 10 >= total}>
                    {isAr ? <ChevronLeft size={16} /> : <ChevronRight size={16} />}
                  </Button>
                </div>
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      {/* Create Dialog */}
      <Dialog open={createOpen} onOpenChange={setCreateOpen}>
        <DialogContent className="max-w-lg" dir={isAr ? 'rtl' : 'ltr'}>
          <DialogHeader>
            <DialogTitle>{tr.createWorkOrder}</DialogTitle>
          </DialogHeader>
          <form onSubmit={handleCreate} className="space-y-4">
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-1.5">
                <Label>{isAr ? 'غرفة التحكم' : 'Control Room'} *</Label>
                <select
                  required
                  value={form.controlRoomId}
                  onChange={e => setForm(f => ({...f, controlRoomId: e.target.value}))}
                  className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
                >
                  <option value="">{isAr ? 'اختر غرفة' : 'Select room'}</option>
                  {controlRooms.map(cr => <option key={cr.id} value={cr.id}>{cr.name}</option>)}
                </select>
              </div>
              <div className="space-y-1.5">
                <Label>{tr.subType} *</Label>
                <select
                  required
                  value={form.subType}
                  onChange={e => setForm(f => ({...f, subType: e.target.value}))}
                  className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
                >
                  <option value="">{isAr ? 'اختر النوع' : 'Select type'}</option>
                  {SUB_TYPES.map(s => <option key={s} value={s}>{s.replace(/_/g,' ')}</option>)}
                </select>
              </div>
            </div>
            <div className="space-y-1.5">
              <Label>{tr.title} *</Label>
              <Input required value={form.title} onChange={e => setForm(f => ({...f, title: e.target.value}))} placeholder={isAr ? 'عنوان البلاغ' : 'Ticket title'} />
            </div>
            <div className="space-y-1.5">
              <Label>{tr.description}</Label>
              <Textarea rows={3} value={form.description} onChange={e => setForm(f => ({...f, description: e.target.value}))} placeholder={isAr ? 'وصف المشكلة' : 'Describe the issue'} />
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-1.5">
                <Label>{tr.location}</Label>
                <Input value={form.locationDetail} onChange={e => setForm(f => ({...f, locationDetail: e.target.value}))} />
              </div>
              <div className="space-y-1.5">
                <Label>{tr.priority}</Label>
                <select
                  value={form.priority}
                  onChange={e => setForm(f => ({...f, priority: e.target.value as TicketPriority}))}
                  className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
                >
                  {PRIORITIES.map(p => <option key={p} value={p}>{p}</option>)}
                </select>
              </div>
            </div>
            <div className="flex justify-end gap-3 pt-2">
              <Button type="button" variant="outline" onClick={() => setCreateOpen(false)}>{tr.cancel}</Button>
              <Button type="submit" disabled={submitting || !form.controlRoomId || !form.title || !form.subType} className="bg-[#0f2744] hover:bg-[#1a3a6b]">
                {submitting ? (isAr ? 'جاري الإرسال...' : 'Submitting...') : tr.submit}
              </Button>
            </div>
          </form>
        </DialogContent>
      </Dialog>
    </DashboardLayout>
  );
}
