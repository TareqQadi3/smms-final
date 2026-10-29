'use client';

import { useEffect, useState, useCallback } from 'react';
import DashboardLayout from '@/components/layout/DashboardLayout';
import { useAuthStore } from '@/store/authStore';
import { useT } from '@/lib/i18n';
import { workOrderApi } from '@/lib/api';
import { WorkOrder } from '@/lib/types';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Textarea } from '@/components/ui/textarea';
import { Label } from '@/components/ui/label';
import { CheckCircle, XCircle } from 'lucide-react';
import { formatDate } from '@/lib/utils';
import { ErrorState, LoadingState } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { workflowDict } from '@/modules/workflow/i18n';
import { canDecideApproval } from '@/modules/workflow/permissions';
import { describeError } from '@/modules/workflow/errors';

export default function ApprovalsPage() {
  const { lang, user } = useAuthStore();
  const { t: wt } = useModuleT(workflowDict);
  const canDecide = canDecideApproval(user?.role); // UI hint only; the server allows operating_company_manager only
  const tr = useT(lang);
  const isAr = lang === 'ar';

  const [pending, setPending] = useState<WorkOrder[]>([]);
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState<WorkOrder | null>(null);
  const [decision, setDecision] = useState<'approved' | 'rejected' | null>(null);
  const [note, setNote] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [loadError, setLoadError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError('');
    try {
      const res = await workOrderApi.getAll({ status: 'waiting_approval', page: 0, size: 50 });
      setPending(res.data.content || []);
    } catch (e) { setLoadError(describeError(e, wt)); }
    finally { setLoading(false); }
  }, [wt]);

  useEffect(() => { load(); }, [load]);

  const handleDecision = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selected || !decision || !canDecide) return;
    if (decision === 'rejected' && !note.trim()) { setActionError(wt('approvalNoteRequired')); return; }
    setSubmitting(true);
    setActionError('');
    try {
      await workOrderApi.approvalDecision(selected.id, decision, note || undefined);
      setSelected(null);
      setDecision(null);
      setNote('');
      setNotice(wt('approvalDone'));
      load();
    } catch (e) {
      // 409 = the ticket changed concurrently: close the dialog and refresh the list
      setActionError(describeError(e, wt));
      if ((e as { response?: { status?: number } })?.response?.status === 409) {
        setSelected(null); setDecision(null); setNotice(describeError(e, wt)); load();
      }
    }
    finally { setSubmitting(false); }
  };

  return (
    <DashboardLayout>
      <div className="max-w-5xl mx-auto space-y-5">
        <div>
          <h1 className="text-2xl font-bold text-foreground">{tr.approvals}</h1>
          <p className="text-sm text-muted-foreground">
            {pending.length} {isAr ? 'طلب بانتظار الموافقة' : 'requests pending approval'}
          </p>
        </div>

        {!canDecide && (
          <p role="note" className="rounded-md border bg-muted/50 px-3 py-2 text-sm text-muted-foreground">{wt('approvalsReadOnly')}</p>
        )}
        {notice && <p role="status" className="text-sm text-green-700">{notice}</p>}

        <Card className="border-0 shadow-sm">
          <CardContent className="p-0">
            {loading ? (
              <LoadingState rows={3} />
            ) : loadError ? (
              <ErrorState message={loadError} onRetry={load} />
            ) : pending.length === 0 ? (
              <div className="flex flex-col items-center justify-center h-48 text-muted-foreground/70">
                <CheckCircle size={40} className="text-green-300 mb-3" />
                <p>{isAr ? 'لا توجد طلبات بانتظار الموافقة' : 'No pending approvals'}</p>
              </div>
            ) : (
              <div className="table-scroll">
                <table className="w-full text-sm">
                  <thead className="bg-muted/50 border-b">
                    <tr>
                      {[tr.ticketNumber, tr.title, isAr ? 'الفني' : 'Technician', isAr ? 'ملاحظات المستودع' : 'Warehouse Notes', tr.date, ...(canDecide ? [tr.actions] : [])].map(h => (
                        <th key={h} className="text-start px-4 py-3 font-medium text-muted-foreground">{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {pending.map(wo => (
                      <tr key={wo.id} className="border-b last:border-0 hover:bg-muted/30">
                        <td className="px-4 py-3 font-mono text-xs text-blue-600 font-semibold whitespace-nowrap tabular-nums"><bdi dir="ltr">{wo.ticketNumber}</bdi></td>
                        <td className="px-4 py-3 font-medium max-w-[200px] truncate">{wo.title}</td>
                        <td className="px-4 py-3 text-muted-foreground">{wo.assignedTo?.fullName || '-'}</td>
                        <td className="px-4 py-3 text-muted-foreground text-xs max-w-[150px] truncate">{wo.warehouseNotes || '-'}</td>
                        <td className="px-4 py-3 text-muted-foreground text-xs whitespace-nowrap tabular-nums">
                          {formatDate(wo.createdAt, lang)}
                        </td>
                        {canDecide && <td className="px-4 py-3">
                          <div className="flex gap-2">
                            <Button
                              size="sm"
                              className="h-7 text-xs gap-1 bg-green-600 hover:bg-green-700"
                              onClick={() => { setSelected(wo); setDecision('approved'); setActionError(''); setNotice(''); }}
                            >
                              <CheckCircle size={13} /> {isAr ? 'موافقة' : 'Approve'}
                            </Button>
                            <Button
                              size="sm"
                              variant="outline"
                              className="h-7 text-xs gap-1 text-red-600 border-red-200 hover:bg-red-50"
                              onClick={() => { setSelected(wo); setDecision('rejected'); setActionError(''); setNotice(''); }}
                            >
                              <XCircle size={13} /> {isAr ? 'رفض' : 'Reject'}
                            </Button>
                          </div>
                        </td>}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      <Dialog open={!!selected && canDecide} onOpenChange={() => { setSelected(null); setDecision(null); setActionError(''); }}>
        <DialogContent className="max-w-sm" dir={isAr ? 'rtl' : 'ltr'}>
          <DialogHeader>
            <DialogTitle>
              {decision === 'approved'
                ? (isAr ? 'تأكيد الموافقة' : 'Confirm Approval')
                : (isAr ? 'تأكيد الرفض' : 'Confirm Rejection')}
            </DialogTitle>
          </DialogHeader>
          <form onSubmit={handleDecision} className="space-y-4">
            {actionError && <p role="alert" className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">{actionError}</p>}
            <p className="text-sm text-muted-foreground">
              {isAr ? 'البلاغ:' : 'Ticket:'} <span className="font-semibold">{selected?.ticketNumber}</span>
            </p>
            <div className="space-y-1.5">
              <Label>{tr.notes} {decision === 'rejected' && '*'}</Label>
              <Textarea
                rows={3}
                required={decision === 'rejected'}
                value={note}
                onChange={e => setNote(e.target.value)}
                placeholder={isAr ? 'أضف ملاحظة...' : 'Add note...'}
              />
            </div>
            <div className="flex justify-end gap-3">
              <Button type="button" variant="outline" onClick={() => { setSelected(null); setDecision(null); }}>{tr.cancel}</Button>
              <Button
                type="submit"
                disabled={submitting}
                className={decision === 'approved' ? 'bg-green-600 hover:bg-green-700' : 'bg-red-600 hover:bg-red-700'}
              >
                {submitting ? (isAr ? 'جاري...' : 'Processing...') : tr.confirm}
              </Button>
            </div>
          </form>
        </DialogContent>
      </Dialog>
    </DashboardLayout>
  );
}
