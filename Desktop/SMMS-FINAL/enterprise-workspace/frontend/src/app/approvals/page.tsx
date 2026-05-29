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
import { format } from 'date-fns';

export default function ApprovalsPage() {
  const { lang } = useAuthStore();
  const tr = useT(lang);
  const isAr = lang === 'ar';

  const [pending, setPending] = useState<WorkOrder[]>([]);
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState<WorkOrder | null>(null);
  const [decision, setDecision] = useState<'approved' | 'rejected' | null>(null);
  const [note, setNote] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await workOrderApi.getAll({ status: 'waiting_approval', page: 0, size: 50 });
      setPending(res.data.content || []);
    } catch { /* silent */ }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); }, [load]);

  const handleDecision = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selected || !decision) return;
    setSubmitting(true);
    try {
      await workOrderApi.approvalDecision(selected.id, decision, note || undefined);
      setSelected(null);
      setDecision(null);
      setNote('');
      load();
    } catch { /* silent */ }
    finally { setSubmitting(false); }
  };

  return (
    <DashboardLayout>
      <div className="max-w-5xl mx-auto space-y-5">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">{tr.approvals}</h1>
          <p className="text-sm text-gray-500">
            {pending.length} {isAr ? 'طلب بانتظار الموافقة' : 'requests pending approval'}
          </p>
        </div>

        <Card className="border-0 shadow-sm">
          <CardContent className="p-0">
            {loading ? (
              <div className="flex items-center justify-center h-48">
                <div className="w-8 h-8 border-4 border-blue-600 border-t-transparent rounded-full animate-spin" />
              </div>
            ) : pending.length === 0 ? (
              <div className="flex flex-col items-center justify-center h-48 text-gray-400">
                <CheckCircle size={40} className="text-green-300 mb-3" />
                <p>{isAr ? 'لا توجد طلبات بانتظار الموافقة' : 'No pending approvals'}</p>
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="bg-gray-50 border-b">
                    <tr>
                      {[tr.ticketNumber, tr.title, isAr ? 'الفني' : 'Technician', isAr ? 'ملاحظات المستودع' : 'Warehouse Notes', tr.date, tr.actions].map(h => (
                        <th key={h} className="text-start px-4 py-3 font-medium text-gray-600">{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {pending.map(wo => (
                      <tr key={wo.id} className="border-b last:border-0 hover:bg-gray-50">
                        <td className="px-4 py-3 font-mono text-xs text-blue-600 font-semibold">{wo.ticketNumber}</td>
                        <td className="px-4 py-3 font-medium max-w-[200px] truncate">{wo.title}</td>
                        <td className="px-4 py-3 text-gray-600">{wo.assignedTo?.fullName || '-'}</td>
                        <td className="px-4 py-3 text-gray-500 text-xs max-w-[150px] truncate">{'-'}</td>
                        <td className="px-4 py-3 text-gray-500 text-xs">
                          {wo.createdAt ? format(new Date(wo.createdAt), 'dd/MM/yyyy') : '-'}
                        </td>
                        <td className="px-4 py-3">
                          <div className="flex gap-2">
                            <Button
                              size="sm"
                              className="h-7 text-xs gap-1 bg-green-600 hover:bg-green-700"
                              onClick={() => { setSelected(wo); setDecision('approved'); }}
                            >
                              <CheckCircle size={13} /> {isAr ? 'موافقة' : 'Approve'}
                            </Button>
                            <Button
                              size="sm"
                              variant="outline"
                              className="h-7 text-xs gap-1 text-red-600 border-red-200 hover:bg-red-50"
                              onClick={() => { setSelected(wo); setDecision('rejected'); }}
                            >
                              <XCircle size={13} /> {isAr ? 'رفض' : 'Reject'}
                            </Button>
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      <Dialog open={!!selected} onOpenChange={() => { setSelected(null); setDecision(null); }}>
        <DialogContent className="max-w-sm" dir={isAr ? 'rtl' : 'ltr'}>
          <DialogHeader>
            <DialogTitle>
              {decision === 'approved'
                ? (isAr ? 'تأكيد الموافقة' : 'Confirm Approval')
                : (isAr ? 'تأكيد الرفض' : 'Confirm Rejection')}
            </DialogTitle>
          </DialogHeader>
          <form onSubmit={handleDecision} className="space-y-4">
            <p className="text-sm text-gray-600">
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
