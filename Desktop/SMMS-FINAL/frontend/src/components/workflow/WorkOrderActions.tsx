'use client';

import { useState } from 'react';
import { CheckCircle, Lock, Package, RotateCcw, ThumbsDown, ThumbsUp, UserCheck, XCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Label } from '@/components/ui/label';
import { useT } from '@/lib/i18n';
import { useAuthStore } from '@/store/authStore';
import { workOrderApi } from '@/lib/api';
import { deriveActions } from '@/modules/workflow/permissions';
import { isConflict, parseApiError } from '@/modules/workflow/errors';
import { workflowDict } from '@/modules/workflow/i18n';
import { translate } from '@/lib/module-i18n';
import type { WorkOrderDetail } from '@/modules/workflow/types';

type ActionType = 'transition' | 'assign' | 'warehouse' | 'approval' | 'close' | 'reopen' | 'requestInfo' | 'provideInfo';
type Decision = 'approved' | 'rejected' | 'purchase_request';
interface Technician { id: string; fullName: string }

const inputClass = 'w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring';

/**
 * Role/status actions of a work order (assign, request/provide information, warehouse, approval,
 * reopen, status transitions, close) with their dialog. Extracted unchanged from the old detail page,
 * plus 409 (optimistic lock) handling: the dialog closes and `onConflict` asks the page to offer a reload.
 */
export function WorkOrderActions({
  wo, onDone, onConflict,
}: {
  wo: WorkOrderDetail;
  onDone: () => void;
  onConflict: () => void;
}) {
  const { lang, user } = useAuthStore();
  const tr = useT(lang);
  const isAr = lang === 'ar';

  const [action, setAction] = useState<ActionType | null>(null);
  const [note, setNote] = useState('');
  const [targetStatus, setTargetStatus] = useState('');
  const [technicianId, setTechnicianId] = useState('');
  const [technicians, setTechnicians] = useState<Technician[]>([]);
  const [materialsAvailable, setMaterialsAvailable] = useState<boolean | null>(null);
  const [warehouseNotes, setWarehouseNotes] = useState('');
  const [approvalDecision, setApprovalDecision] = useState<Decision | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');

  const statusLabel: Record<string, string> = {
    new: isAr ? 'جديد' : 'New',
    under_review: isAr ? 'قيد المراجعة' : 'Under Review',
    assigned: isAr ? 'مُعيَّن' : 'Assigned',
    in_progress: isAr ? 'قيد التنفيذ' : 'In Progress',
    waiting_materials: isAr ? 'انتظار مواد' : 'Waiting Materials',
    waiting_approval: isAr ? 'انتظار موافقة' : 'Waiting Approval',
    completed: isAr ? 'مكتمل' : 'Completed',
    closed: isAr ? 'مغلق' : 'Closed',
    cancelled: isAr ? 'ملغي' : 'Cancelled',
  };

  const a = deriveActions(wo, user);

  const openAction = async (type: ActionType, preset?: { status?: string; decision?: Decision }) => {
    setAction(type);
    setNote(''); setTargetStatus(preset?.status ?? ''); setTechnicianId('');
    setMaterialsAvailable(null); setWarehouseNotes('');
    setApprovalDecision(preset?.decision ?? null); setError('');
    if (type === 'assign') {
      const res = await workOrderApi.getAssignableTechnicians(wo.id).catch(() => null);
      setTechnicians(Array.isArray(res?.data) ? res.data : []);
    }
  };
  const closeDialog = () => { setAction(null); setError(''); };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setError('');
    try {
      switch (action) {
        case 'transition':
          if (!targetStatus) throw new Error(isAr ? 'اختر الحالة' : 'Select status');
          await workOrderApi.transition(wo.id, { targetStatus, note: note || undefined });
          break;
        case 'assign':
          if (!technicianId) throw new Error(isAr ? 'اختر فنياً' : 'Select technician');
          await workOrderApi.assign(wo.id, technicianId, note || undefined);
          break;
        case 'warehouse':
          if (materialsAvailable === null) throw new Error(isAr ? 'حدد توفر المواد' : 'Specify materials availability');
          await workOrderApi.warehouseAction(wo.id, materialsAvailable, warehouseNotes || undefined);
          break;
        case 'approval':
          if (!approvalDecision) throw new Error(isAr ? 'اختر القرار' : 'Select decision');
          if (approvalDecision === 'rejected' && !note.trim()) throw new Error(translate(workflowDict, lang, 'approvalNoteRequired'));
          await workOrderApi.approvalDecision(wo.id, approvalDecision, note || undefined);
          break;
        case 'close':
          await workOrderApi.close(wo.id, note || undefined);
          break;
        case 'reopen':
          if (!note) throw new Error(isAr ? 'يجب إضافة سبب' : 'Note is required');
          await workOrderApi.reopen(wo.id, note);
          break;
        case 'requestInfo':
          if (!note.trim()) throw new Error(isAr ? 'اكتب المعلومات المطلوبة' : 'Describe the information needed');
          await workOrderApi.requestInfo(wo.id, note.trim());
          break;
        case 'provideInfo':
          if (!note.trim()) throw new Error(isAr ? 'اكتب المعلومات' : 'Enter the information');
          await workOrderApi.provideInfo(wo.id, note.trim());
          break;
      }
      closeDialog();
      onDone();
    } catch (err) {
      if (isConflict(err)) {
        closeDialog();
        onConflict();
      } else {
        const msg = parseApiError(err).message;
        setError(msg || (isAr ? 'حدث خطأ' : 'An error occurred'));
      }
    } finally {
      setSubmitting(false);
    }
  };

  const any = a.transitions.length > 0 || a.canAssign || a.canRequestInfo || a.canProvideInfo || a.canWarehouse || a.canApproval || a.canReopen;

  return (
    <>
      {any && (
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-sm">{isAr ? 'الإجراءات المتاحة' : 'Available Actions'}</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2">
            {a.canAssign && (
              <Button className="w-full justify-start gap-2" size="sm" onClick={() => openAction('assign')}>
                <UserCheck size={15} aria-hidden />{tr.assignTechnician}
              </Button>
            )}
            {a.canRequestInfo && (
              <Button className="w-full justify-start gap-2" size="sm" variant="outline" onClick={() => openAction('requestInfo')}>
                <RotateCcw size={15} aria-hidden />{isAr ? 'طلب معلومات إضافية' : 'Request Information'}
              </Button>
            )}
            {a.canProvideInfo && (
              <Button className="w-full justify-start gap-2" size="sm" onClick={() => openAction('provideInfo')}>
                <CheckCircle size={15} aria-hidden />{isAr ? 'تقديم المعلومات المطلوبة' : 'Provide Information'}
              </Button>
            )}
            {a.canWarehouse && (
              <Button className="w-full justify-start gap-2" size="sm" variant="outline" onClick={() => openAction('warehouse')}>
                <Package size={15} aria-hidden />{isAr ? 'قرار المستودع' : 'Warehouse Decision'}
              </Button>
            )}
            {a.canApproval && (
              <>
                <Button className="w-full justify-start gap-2 bg-green-600 hover:bg-green-700" size="sm" onClick={() => openAction('approval', { decision: 'approved' })}>
                  <ThumbsUp size={15} aria-hidden />{isAr ? 'موافقة' : 'Approve'}
                </Button>
                <Button className="w-full justify-start gap-2" size="sm" variant="outline" onClick={() => openAction('approval', { decision: 'purchase_request' })}>
                  <Package size={15} aria-hidden />{isAr ? 'طلب شراء' : 'Purchase Request'}
                </Button>
                <Button className="w-full justify-start gap-2 border-destructive/30 text-destructive hover:bg-destructive/10" size="sm" variant="outline" onClick={() => openAction('approval', { decision: 'rejected' })}>
                  <ThumbsDown size={15} aria-hidden />{isAr ? 'رفض' : 'Reject'}
                </Button>
              </>
            )}
            {a.canReopen && (
              <Button className="w-full justify-start gap-2" size="sm" variant="outline" onClick={() => openAction('reopen')}>
                <RotateCcw size={15} aria-hidden />{isAr ? 'إعادة فتح' : 'Reopen'}
              </Button>
            )}
            {a.transitions.map((ts) => {
              const isClose = ts === 'closed';
              const isCancel = ts === 'cancelled';
              return (
                <Button
                  key={ts}
                  className={`w-full justify-start gap-2 ${isClose ? 'bg-gray-700 hover:bg-gray-800' : isCancel ? 'border-destructive/30 text-destructive hover:bg-destructive/10' : ''}`}
                  size="sm"
                  variant={isCancel ? 'outline' : 'default'}
                  onClick={() => (isClose ? openAction('close') : openAction('transition', { status: ts }))}
                >
                  {isClose ? <Lock size={15} aria-hidden /> : isCancel ? <XCircle size={15} aria-hidden /> : <CheckCircle size={15} aria-hidden />}
                  {statusLabel[ts] || ts}
                </Button>
              );
            })}
          </CardContent>
        </Card>
      )}

      <Dialog open={!!action} onOpenChange={closeDialog}>
        <DialogContent className="max-w-sm" dir={isAr ? 'rtl' : 'ltr'}>
          <DialogHeader>
            <DialogTitle>
              {action === 'assign' && (isAr ? 'تعيين فني' : 'Assign Technician')}
              {action === 'warehouse' && (isAr ? 'قرار المستودع' : 'Warehouse Decision')}
              {action === 'approval' && (isAr ? 'قرار الموافقة' : 'Approval Decision')}
              {action === 'close' && (isAr ? 'إغلاق البلاغ' : 'Close Ticket')}
              {action === 'reopen' && (isAr ? 'إعادة فتح البلاغ' : 'Reopen Ticket')}
              {action === 'requestInfo' && (isAr ? 'طلب معلومات إضافية' : 'Request Information')}
              {action === 'provideInfo' && (isAr ? 'تقديم المعلومات' : 'Provide Information')}
              {action === 'transition' && (isAr ? `تحويل إلى: ${statusLabel[targetStatus] || targetStatus}` : `Transition to: ${statusLabel[targetStatus] || targetStatus}`)}
            </DialogTitle>
          </DialogHeader>

          <form onSubmit={handleSubmit} className="space-y-4">
            {error && <p role="alert" className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">{error}</p>}

            {action === 'assign' && (
              <div className="space-y-1.5">
                <Label htmlFor="wo-tech">{tr.selectTechnician}</Label>
                <select id="wo-tech" required value={technicianId} onChange={(e) => setTechnicianId(e.target.value)} className={inputClass}>
                  <option value="">{isAr ? 'اختر فنياً' : 'Select technician'}</option>
                  {technicians.map((t) => <option key={t.id} value={t.id}>{t.fullName}</option>)}
                </select>
              </div>
            )}

            {action === 'warehouse' && (
              <>
                <div className="space-y-1.5">
                  <Label>{isAr ? 'هل المواد متوفرة؟' : 'Are materials available?'}</Label>
                  <div className="flex gap-3" role="group">
                    <button type="button" aria-pressed={materialsAvailable === true} onClick={() => setMaterialsAvailable(true)}
                      className={`flex-1 rounded-md border py-2 text-sm font-medium transition-colors ${materialsAvailable === true ? 'border-green-600 bg-green-600 text-white' : 'border-border hover:bg-accent'}`}>
                      {isAr ? 'نعم، متوفرة' : 'Yes, available'}
                    </button>
                    <button type="button" aria-pressed={materialsAvailable === false} onClick={() => setMaterialsAvailable(false)}
                      className={`flex-1 rounded-md border py-2 text-sm font-medium transition-colors ${materialsAvailable === false ? 'border-red-600 bg-red-600 text-white' : 'border-border hover:bg-accent'}`}>
                      {isAr ? 'لا، غير متوفرة' : 'No, not available'}
                    </button>
                  </div>
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="wo-wh-notes">{tr.notes}</Label>
                  <textarea id="wo-wh-notes" rows={3} value={warehouseNotes} onChange={(e) => setWarehouseNotes(e.target.value)} className={inputClass} placeholder={isAr ? 'ملاحظات...' : 'Notes...'} />
                </div>
              </>
            )}

            {action === 'approval' && (
              <>
                <div className={`flex items-center gap-2 rounded-md px-3 py-2 text-sm font-medium ${approvalDecision === 'rejected' ? 'border border-red-200 bg-red-50 text-red-800' : 'border border-green-200 bg-green-50 text-green-800'}`}>
                  {approvalDecision === 'approved' && (isAr ? 'موافقة' : 'Approve')}
                  {approvalDecision === 'purchase_request' && (isAr ? 'طلب شراء' : 'Purchase Request')}
                  {approvalDecision === 'rejected' && (isAr ? 'رفض' : 'Reject')}
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="wo-note">{tr.notes} {approvalDecision === 'rejected' && '*'}</Label>
                  <textarea id="wo-note" rows={3} required={approvalDecision === 'rejected'} value={note} onChange={(e) => setNote(e.target.value)} className={inputClass} placeholder={isAr ? 'أضف ملاحظة...' : 'Add note...'} />
                </div>
              </>
            )}

            {action === 'transition' && (
              <div className="space-y-1.5">
                <Label htmlFor="wo-note">{tr.notes}</Label>
                <textarea id="wo-note" rows={3} value={note} onChange={(e) => setNote(e.target.value)} className={inputClass} placeholder={isAr ? 'ملاحظات اختيارية' : 'Optional notes'} />
              </div>
            )}

            {(action === 'requestInfo' || action === 'provideInfo') && (
              <div className="space-y-1.5">
                <Label htmlFor="wo-note">{tr.notes} *</Label>
                <textarea id="wo-note" rows={3} required maxLength={1000} value={note} onChange={(e) => setNote(e.target.value)} className={inputClass}
                  placeholder={action === 'requestInfo'
                    ? (isAr ? 'ما المعلومات المطلوبة من مقدم البلاغ؟' : 'What do you need from the requester?')
                    : (isAr ? 'اكتب المعلومات المطلوبة...' : 'Write the requested information...')} />
              </div>
            )}

            {(action === 'close' || action === 'reopen') && (
              <div className="space-y-1.5">
                <Label htmlFor="wo-note">{tr.notes} {action === 'reopen' && '*'}</Label>
                <textarea id="wo-note" rows={3} required={action === 'reopen'} value={note} onChange={(e) => setNote(e.target.value)} className={inputClass} placeholder={isAr ? 'أضف ملاحظة...' : 'Add note...'} />
              </div>
            )}

            <div className="flex justify-end gap-3 pt-1">
              <Button type="button" variant="outline" onClick={closeDialog}>{tr.cancel}</Button>
              <Button type="submit" disabled={submitting || (action === 'warehouse' && materialsAvailable === null)}>
                {submitting ? (isAr ? 'جاري...' : 'Processing...') : tr.confirm}
              </Button>
            </div>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}
