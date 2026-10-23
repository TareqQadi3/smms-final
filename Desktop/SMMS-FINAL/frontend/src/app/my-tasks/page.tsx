'use client';

import { useEffect, useState, useCallback } from 'react';
import DashboardLayout from '@/components/layout/DashboardLayout';
import { useAuthStore } from '@/store/authStore';
import { useT, statusColors, priorityColors, tStatus, tPriority } from '@/lib/i18n';
import { workOrderApi } from '@/lib/api';
import { WorkOrder } from '@/lib/types';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Textarea } from '@/components/ui/textarea';
import { Label } from '@/components/ui/label';
import { Eye, PlayCircle } from 'lucide-react';
import Link from 'next/link';
import { StateMessage } from '@/components/ui/state-message';

export default function MyTasksPage() {
  const { lang, user } = useAuthStore();
  const tr = useT(lang);
  const isAr = lang === 'ar';

  const [tasks, setTasks] = useState<WorkOrder[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [actionOpen, setActionOpen] = useState(false);
  const [selectedWo, setSelectedWo] = useState<WorkOrder | null>(null);
  const [actionStatus, setActionStatus] = useState('');
  const [note, setNote] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError(false);
    try {
      const res = await workOrderApi.getAll({ assignedToId: user?.id, page: 0, size: 50 });
      setTasks(res.data.content || []);
    } catch { setLoadError(true); }
    finally { setLoading(false); }
  }, [user?.id]);

  useEffect(() => { load(); }, [load]);

  const openAction = (wo: WorkOrder) => {
    setSelectedWo(wo);
    setActionStatus('');
    setNote('');
    setActionOpen(true);
  };

  const techActions: Record<string, string[]> = {
    assigned: ['in_progress', 'cancelled'],
    in_progress: ['waiting_materials', 'completed', 'cancelled'],
    waiting_materials: ['in_progress'],
  };

  const handleAction = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedWo || !actionStatus) return;
    setSubmitting(true);
    try {
      await workOrderApi.transition(selectedWo.id, { targetStatus: actionStatus, note });
      setActionOpen(false);
      load();
    } catch { /* silent */ }
    finally { setSubmitting(false); }
  };

  const statusLabel: Record<string, string> = {
    assigned: isAr ? 'مُعيَّن' : 'Assigned',
    in_progress: isAr ? 'قيد التنفيذ' : 'In Progress',
    waiting_materials: isAr ? 'انتظار مواد' : 'Waiting Materials',
    completed: isAr ? 'مكتمل' : 'Completed',
    cancelled: isAr ? 'ملغي' : 'Cancelled',
  };

  const active = tasks.filter(t => !['completed','closed','cancelled'].includes(t.status));
  const done = tasks.filter(t => ['completed','closed'].includes(t.status));

  return (
    <DashboardLayout>
      <div className="max-w-4xl mx-auto space-y-6">
        <div>
          <h1 className="text-2xl font-bold text-foreground">{tr.myTasks}</h1>
          <p className="text-sm text-muted-foreground">{active.length} {isAr ? 'مهمة نشطة' : 'active tasks'}</p>
        </div>

        {/* Stats */}
        <div className="grid grid-cols-3 gap-4">
          {[
            { label: isAr ? 'مُعيَّنة' : 'Assigned', value: tasks.filter(t => t.status === 'assigned').length, color: 'text-blue-600' },
            { label: isAr ? 'قيد التنفيذ' : 'In Progress', value: tasks.filter(t => t.status === 'in_progress').length, color: 'text-orange-600' },
            { label: isAr ? 'مكتملة' : 'Completed', value: done.length, color: 'text-green-700 dark:text-green-500' },
          ].map(s => (
            <Card key={s.label} className="border-0 shadow-sm">
              <CardContent className="p-4 text-center">
                <p className={`text-3xl font-bold ${s.color}`}>{s.value}</p>
                <p className="text-xs text-muted-foreground mt-1">{s.label}</p>
              </CardContent>
            </Card>
          ))}
        </div>

        {/* Active Tasks */}
        <Card className="border-0 shadow-sm">
          <CardHeader className="pb-2">
            <CardTitle className="text-base">{isAr ? 'المهام النشطة' : 'Active Tasks'}</CardTitle>
          </CardHeader>
          <CardContent className="p-0">
            {loading ? (
              <div className="flex items-center justify-center h-32">
                <div className="w-8 h-8 border-4 border-blue-600 border-t-transparent rounded-full animate-spin" />
              </div>
            ) : loadError ? (
              <StateMessage kind="error" onRetry={load} retryLabel={isAr ? 'إعادة المحاولة' : 'Try again'}>
                {isAr ? 'تعذّر تحميل مهامك. تحقق من الاتصال ثم أعد المحاولة.' : 'Unable to load your tasks. Check your connection and try again.'}
              </StateMessage>
            ) : active.length === 0 ? (
              <StateMessage>{isAr ? 'لا توجد مهام نشطة حالياً.' : 'You have no active tasks.'}</StateMessage>
            ) : (
              <div className="divide-y">
                {active.map(wo => (
                  <div key={wo.id} className="px-4 py-4 hover:bg-muted/30 flex items-center justify-between gap-4">
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 mb-1">
                        <span className="font-mono text-xs text-blue-600 font-semibold whitespace-nowrap tabular-nums"><bdi dir="ltr">{wo.ticketNumber}</bdi></span>
                        <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${statusColors[wo.status] || ''}`}>
                          {tStatus(wo.status, lang)}
                        </span>
                        <span className={`px-1.5 py-0.5 rounded text-xs border ${priorityColors[wo.priority] || ''}`}>
                          {tPriority(wo.priority, lang)}
                        </span>
                      </div>
                      <p className="font-medium text-sm truncate">{wo.title}</p>
                      {wo.locationDetail && <p className="text-xs text-muted-foreground mt-0.5">{wo.locationDetail}</p>}
                    </div>
                    <div className="flex gap-2 shrink-0">
                      <Link href={`/work-orders/${wo.id}`} className="inline-flex">
                        <Button variant="outline" size="sm" className="gap-1.5 text-xs">
                          <Eye size={13} /> {isAr ? 'عرض' : 'View'}
                        </Button>
                      </Link>
                      {techActions[wo.status] && (
                        <Button size="sm" className="gap-1.5 text-xs bg-[#0f2744] hover:bg-[#1a3a6b]" onClick={() => openAction(wo)}>
                          <PlayCircle size={13} /> {isAr ? 'تحديث' : 'Update'}
                        </Button>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      {/* Action Dialog */}
      <Dialog open={actionOpen} onOpenChange={setActionOpen}>
        <DialogContent className="max-w-sm" dir={isAr ? 'rtl' : 'ltr'}>
          <DialogHeader>
            <DialogTitle>{isAr ? 'تحديث حالة البلاغ' : 'Update Ticket Status'}</DialogTitle>
          </DialogHeader>
          <form onSubmit={handleAction} className="space-y-4">
            <div className="space-y-1.5">
              <Label>{isAr ? 'الحالة الجديدة' : 'New Status'}</Label>
              <select
                value={actionStatus}
                onChange={e => setActionStatus(e.target.value)}
                className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
              >
                <option value="">{isAr ? 'اختر الحالة' : 'Select status'}</option>
                {(techActions[selectedWo?.status || ''] || []).map(s => (
                  <option key={s} value={s}>{statusLabel[s] || s}</option>
                ))}
              </select>
            </div>
            <div className="space-y-1.5">
              <Label>{tr.notes}</Label>
              <Textarea rows={3} value={note} onChange={e => setNote(e.target.value)} placeholder={isAr ? 'ملاحظات اختيارية' : 'Optional notes'} />
            </div>
            <div className="flex justify-end gap-3">
              <Button type="button" variant="outline" onClick={() => setActionOpen(false)}>{tr.cancel}</Button>
              <Button type="submit" disabled={!actionStatus || submitting} className="bg-[#0f2744] hover:bg-[#1a3a6b]">
                {submitting ? (isAr ? 'جاري الحفظ...' : 'Saving...') : tr.save}
              </Button>
            </div>
          </form>
        </DialogContent>
      </Dialog>
    </DashboardLayout>
  );
}
