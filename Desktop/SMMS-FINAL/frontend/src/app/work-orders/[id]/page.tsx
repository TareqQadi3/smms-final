'use client';

import { useEffect, useState, useCallback } from 'react';
import { useParams, useRouter } from 'next/navigation';
import DashboardLayout from '@/components/layout/DashboardLayout';
import { useAuthStore } from '@/store/authStore';
import { useT, statusColors, priorityColors, tStatus, tPriority } from '@/lib/i18n';
import { workOrderApi, userApi } from '@/lib/api';
import { WorkOrder, TicketHistory } from '@/lib/types';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Textarea } from '@/components/ui/textarea';
import { Label } from '@/components/ui/label';
import {
  ArrowRight, Clock, User, CheckCircle, XCircle,
  UserCheck, Package, ThumbsUp, ThumbsDown, RotateCcw, Lock
} from 'lucide-react';
import { format } from 'date-fns';

type ActionType =
  | 'transition'
  | 'assign'
  | 'warehouse'
  | 'approval'
  | 'close'
  | 'reopen';

interface Technician { id: string; fullName: string; }

export default function WorkOrderDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { lang, user } = useAuthStore();
  const tr = useT(lang);
  const router = useRouter();
  const isAr = lang === 'ar';

  const [wo, setWo] = useState<WorkOrder | null>(null);
  const [history, setHistory] = useState<TicketHistory[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  // Action dialog
  const [action, setAction] = useState<ActionType | null>(null);
  const [note, setNote] = useState('');
  const [targetStatus, setTargetStatus] = useState('');
  const [technicianId, setTechnicianId] = useState('');
  const [technicians, setTechnicians] = useState<Technician[]>([]);
  const [materialsAvailable, setMaterialsAvailable] = useState<boolean | null>(null);
  const [warehouseNotes, setWarehouseNotes] = useState('');
  const [approvalDecision, setApprovalDecision] = useState<'approved' | 'rejected' | 'purchase_request' | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const load = useCallback(async () => {
    try {
      const [woRes, histRes] = await Promise.all([
        workOrderApi.getById(id),
        workOrderApi.getHistory(id),
      ]);
      setWo(woRes.data);
      setHistory(histRes.data || []);
    } catch {
      setError(isAr ? 'تعذّر تحميل البلاغ' : 'Failed to load work order');
    } finally {
      setLoading(false);
    }
  }, [id, isAr]);

  useEffect(() => { load(); }, [load]);

  const openAction = async (type: ActionType) => {
    setAction(type);
    setNote(''); setTargetStatus(''); setTechnicianId('');
    setMaterialsAvailable(null); setWarehouseNotes('');
    setApprovalDecision(null); setError('');

    if (type === 'assign' && wo?.center?.id) {
      const res = await userApi.getTechnicians(wo.center.id).catch(() => null);
      setTechnicians(res?.data?.content || res?.data || []);
    }
  };

  const closeDialog = () => { setAction(null); setError(''); };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!wo) return;
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
          await workOrderApi.approvalDecision(wo.id, approvalDecision, note || undefined);
          break;
        case 'close':
          await workOrderApi.close(wo.id, note || undefined);
          break;
        case 'reopen':
          if (!note) throw new Error(isAr ? 'يجب إضافة سبب' : 'Note is required');
          await workOrderApi.reopen(wo.id, note);
          break;
      }
      closeDialog();
      setLoading(true);
      await load();
    } catch (e: unknown) {
      const msg = (e as { response?: { data?: { message?: string } }, message?: string })
        ?.response?.data?.message || (e as { message?: string })?.message || '';
      setError(msg || (isAr ? 'حدث خطأ' : 'An error occurred'));
    } finally {
      setSubmitting(false);
    }
  };

  const statusLabel: Record<string, string> = {
    new:               isAr ? 'جديد' : 'New',
    under_review:      isAr ? 'قيد المراجعة' : 'Under Review',
    assigned:          isAr ? 'مُعيَّن' : 'Assigned',
    in_progress:       isAr ? 'قيد التنفيذ' : 'In Progress',
    waiting_materials: isAr ? 'انتظار مواد' : 'Waiting Materials',
    waiting_approval:  isAr ? 'انتظار موافقة' : 'Waiting Approval',
    completed:         isAr ? 'مكتمل' : 'Completed',
    closed:            isAr ? 'مغلق' : 'Closed',
    cancelled:         isAr ? 'ملغي' : 'Cancelled',
  };

  // Available transitions per role + status
  const getTransitions = (): string[] => {
    if (!wo || !user) return [];
    const role = user.role;
    const status = wo.status;
    const map: Record<string, Record<string, string[]>> = {
      control_room: {
        new:       ['under_review', 'cancelled'],
        completed: ['closed'],
      },
      technician: {
        assigned:          ['in_progress'],
        in_progress:       ['waiting_materials', 'completed'],
        waiting_materials: ['in_progress'],
      },
      maintenance_manager: {
        in_progress: ['completed'],
        completed:   ['closed'],
      },
      system_admin: {
        new: ['under_review', 'cancelled'],
        under_review: ['cancelled'],
        in_progress: ['completed', 'cancelled'],
        completed: ['closed'],
        waiting_materials: ['in_progress'],
        waiting_approval: ['in_progress'],
      },
    };
    return map[role]?.[status] || [];
  };

  const canAssign     = wo && user?.role === 'control_room'               && wo.status === 'under_review';
  const canWarehouse  = wo && user?.role === 'warehouse'                   && wo.status === 'waiting_materials';
  const canApproval   = wo && user?.role === 'operating_company_manager'  && wo.status === 'waiting_approval';
  const canReopen     = wo && ['control_room', 'system_admin'].includes(user?.role || '') && wo.status === 'completed';
  const transitions   = getTransitions();

  const inputClass = 'w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring';

  if (loading) return (
    <DashboardLayout>
      <div className="flex items-center justify-center h-64">
        <div className="w-10 h-10 border-4 border-primary border-t-transparent rounded-full animate-spin" />
      </div>
    </DashboardLayout>
  );

  if (!wo) return (
    <DashboardLayout>
      <div className="text-center py-20 text-muted-foreground">{tr.noData}</div>
    </DashboardLayout>
  );

  return (
    <DashboardLayout>
      <div className="max-w-4xl mx-auto space-y-5">
        {/* Back */}
        <Button variant="ghost" size="sm" onClick={() => router.back()} className="gap-1.5">
          {isAr ? <ArrowRight size={16} /> : <ArrowRight size={16} className="rotate-180" />}
          {isAr ? 'رجوع' : 'Back'}
        </Button>

        {/* Header */}
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <p className="text-xs font-mono text-primary font-semibold">{wo.ticketNumber}</p>
            <h1 className="text-xl font-bold text-foreground mt-1">{wo.title}</h1>
          </div>
          <div className="flex gap-2 flex-wrap">
            <span className={`px-3 py-1 rounded-full text-sm font-medium ${statusColors[wo.status] || 'bg-gray-100'}`}>
              {tStatus(wo.status, lang)}
            </span>
            <span className={`px-2 py-1 rounded text-sm font-medium border ${priorityColors[wo.priority] || ''}`}>
              {tPriority(wo.priority, lang)}
            </span>
            {wo.slaBreached && (
              <span className="px-2 py-1 rounded text-sm font-medium bg-red-100 text-red-700 border border-red-300">
                ⚠ SLA
              </span>
            )}
          </div>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-5">
          {/* Main content */}
          <div className="lg:col-span-2 space-y-5">

            {/* Details card */}
            <Card>
              <CardHeader className="pb-2">
                <CardTitle className="text-base">{isAr ? 'التفاصيل' : 'Details'}</CardTitle>
              </CardHeader>
              <CardContent className="space-y-3">
                {wo.description && (
                  <div>
                    <p className="text-xs text-muted-foreground font-medium mb-1">{tr.description}</p>
                    <p className="text-sm bg-muted/50 p-3 rounded-lg">{wo.description}</p>
                  </div>
                )}
                <div className="grid grid-cols-2 gap-4 text-sm">
                  <div>
                    <p className="text-xs text-muted-foreground font-medium">{tr.subType}</p>
                    <p className="font-medium capitalize">{wo.subType}</p>
                  </div>
                  {wo.locationDetail && (
                    <div>
                      <p className="text-xs text-muted-foreground font-medium">{tr.location}</p>
                      <p className="font-medium">{wo.locationDetail}</p>
                    </div>
                  )}
                  <div>
                    <p className="text-xs text-muted-foreground font-medium">{tr.requestedBy}</p>
                    <p className="font-medium">{wo.requestedBy?.fullName || '-'}</p>
                  </div>
                  <div>
                    <p className="text-xs text-muted-foreground font-medium">{tr.assignedTo}</p>
                    <p className="font-medium">{wo.assignedTo?.fullName || '-'}</p>
                  </div>
                  {wo.createdAt && (
                    <div>
                      <p className="text-xs text-muted-foreground font-medium">{isAr ? 'تاريخ الإنشاء' : 'Created'}</p>
                      <p className="font-medium">{format(new Date(wo.createdAt), 'dd/MM/yyyy HH:mm')}</p>
                    </div>
                  )}
                  {wo.slaDeadline && (
                    <div>
                      <p className="text-xs text-muted-foreground font-medium">{tr.slaDeadline}</p>
                      <p className={`font-medium ${wo.slaBreached ? 'text-red-600' : ''}`}>
                        {format(new Date(wo.slaDeadline), 'dd/MM/yyyy HH:mm')}
                        {wo.slaBreached && ' ⚠'}
                      </p>
                    </div>
                  )}
                  {wo.techReport && (
                    <div className="col-span-2">
                      <p className="text-xs text-muted-foreground font-medium">{isAr ? 'تقرير الفني' : 'Tech Report'}</p>
                      <p className="font-medium text-sm">{wo.techReport}</p>
                    </div>
                  )}
                  {wo.warehouseNotes && (
                    <div className="col-span-2">
                      <p className="text-xs text-muted-foreground font-medium">{isAr ? 'ملاحظات المستودع' : 'Warehouse Notes'}</p>
                      <p className="font-medium text-sm">{wo.warehouseNotes}</p>
                    </div>
                  )}
                </div>
              </CardContent>
            </Card>

            {/* Timeline */}
            <Card>
              <CardHeader className="pb-2">
                <CardTitle className="text-base">{tr.timeline}</CardTitle>
              </CardHeader>
              <CardContent>
                {history.length === 0 ? (
                  <p className="text-muted-foreground text-sm text-center py-4">{tr.noData}</p>
                ) : (
                  <div className="space-y-0">
                    {history.map((h, i) => (
                      <div key={h.id} className="flex gap-3">
                        <div className="flex flex-col items-center">
                          <div className={`w-8 h-8 rounded-full flex items-center justify-center shrink-0 ${
                            h.toStatus === 'completed' || h.toStatus === 'closed' ? 'bg-green-100 text-green-600' :
                            h.toStatus === 'cancelled' ? 'bg-red-100 text-red-600' : 'bg-blue-100 text-blue-600'
                          }`}>
                            {h.toStatus === 'completed' || h.toStatus === 'closed'
                              ? <CheckCircle size={16} />
                              : h.toStatus === 'cancelled'
                              ? <XCircle size={16} />
                              : <Clock size={16} />}
                          </div>
                          {i < history.length - 1 && (
                            <div className="w-0.5 flex-1 bg-border my-1 min-h-[20px]" />
                          )}
                        </div>
                        <div className="pb-4 flex-1 min-w-0">
                          <div className="flex flex-wrap items-center gap-2">
                            <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${statusColors[h.toStatus] || 'bg-gray-100'}`}>
                              {tStatus(h.toStatus, lang)}
                            </span>
                            {h.fromStatus && (
                              <span className="text-xs text-muted-foreground">
                                {isAr ? 'من:' : 'from:'} {tStatus(h.fromStatus, lang)}
                              </span>
                            )}
                          </div>
                          {h.note && <p className="text-sm text-foreground mt-1">{h.note}</p>}
                          <div className="flex gap-3 mt-1 text-xs text-muted-foreground">
                            {h.actor && (
                              <span className="flex items-center gap-1">
                                <User size={11} />{h.actor.fullName}
                              </span>
                            )}
                            <span>{h.createdAt ? format(new Date(h.createdAt), 'dd/MM HH:mm') : ''}</span>
                          </div>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </CardContent>
            </Card>
          </div>

          {/* Sidebar */}
          <div className="space-y-4">
            {/* Location */}
            <Card>
              <CardHeader className="pb-2">
                <CardTitle className="text-sm">{isAr ? 'معلومات الموقع' : 'Location'}</CardTitle>
              </CardHeader>
              <CardContent className="space-y-2 text-sm">
                {wo.center && (
                  <div>
                    <p className="text-xs text-muted-foreground">{tr.center}</p>
                    <p className="font-medium">{wo.center.name}</p>
                  </div>
                )}
                {wo.controlRoom && (
                  <div>
                    <p className="text-xs text-muted-foreground">{tr.controlRoom}</p>
                    <p className="font-medium">{wo.controlRoom.name}</p>
                  </div>
                )}
              </CardContent>
            </Card>

            {/* Actions */}
            {(transitions.length > 0 || canAssign || canWarehouse || canApproval || canReopen) && (
              <Card>
                <CardHeader className="pb-2">
                  <CardTitle className="text-sm">{isAr ? 'الإجراءات المتاحة' : 'Available Actions'}</CardTitle>
                </CardHeader>
                <CardContent className="space-y-2">

                  {/* Assign technician */}
                  {canAssign && (
                    <Button
                      className="w-full gap-2 justify-start"
                      size="sm"
                      onClick={() => openAction('assign')}
                    >
                      <UserCheck size={15} />
                      {tr.assignTechnician}
                    </Button>
                  )}

                  {/* Warehouse decision */}
                  {canWarehouse && (
                    <Button
                      className="w-full gap-2 justify-start"
                      size="sm"
                      variant="outline"
                      onClick={() => openAction('warehouse')}
                    >
                      <Package size={15} />
                      {isAr ? 'قرار المستودع' : 'Warehouse Decision'}
                    </Button>
                  )}

                  {/* Approval decision */}
                  {canApproval && (
                    <>
                      <Button
                        className="w-full gap-2 justify-start bg-green-600 hover:bg-green-700"
                        size="sm"
                        onClick={() => { openAction('approval'); setApprovalDecision('approved'); }}
                      >
                        <ThumbsUp size={15} />
                        {isAr ? 'موافقة' : 'Approve'}
                      </Button>
                      <Button
                        className="w-full gap-2 justify-start"
                        size="sm"
                        variant="outline"
                        onClick={() => { openAction('approval'); setApprovalDecision('purchase_request'); }}
                      >
                        <Package size={15} />
                        {isAr ? 'طلب شراء' : 'Purchase Request'}
                      </Button>
                      <Button
                        className="w-full gap-2 justify-start text-destructive border-destructive/30 hover:bg-destructive/10"
                        size="sm"
                        variant="outline"
                        onClick={() => { openAction('approval'); setApprovalDecision('rejected'); }}
                      >
                        <ThumbsDown size={15} />
                        {isAr ? 'رفض' : 'Reject'}
                      </Button>
                    </>
                  )}

                  {/* Reopen */}
                  {canReopen && (
                    <Button
                      className="w-full gap-2 justify-start"
                      size="sm"
                      variant="outline"
                      onClick={() => openAction('reopen')}
                    >
                      <RotateCcw size={15} />
                      {isAr ? 'إعادة فتح' : 'Reopen'}
                    </Button>
                  )}

                  {/* Status transitions */}
                  {transitions.map(ts => {
                    const isClose  = ts === 'closed';
                    const isCancel = ts === 'cancelled';
                    return (
                      <Button
                        key={ts}
                        className={`w-full gap-2 justify-start ${
                          isClose  ? 'bg-gray-700 hover:bg-gray-800' :
                          isCancel ? 'text-destructive border-destructive/30 hover:bg-destructive/10' : ''
                        }`}
                        size="sm"
                        variant={isCancel ? 'outline' : isClose ? 'default' : 'default'}
                        onClick={() => {
                          if (isClose) { openAction('close'); }
                          else { openAction('transition'); setTargetStatus(ts); }
                        }}
                      >
                        {isClose ? <Lock size={15} /> : isCancel ? <XCircle size={15} /> : <CheckCircle size={15} />}
                        {statusLabel[ts] || ts}
                      </Button>
                    );
                  })}
                </CardContent>
              </Card>
            )}
          </div>
        </div>
      </div>

      {/* ── Action Dialog ─────────────────────────────────── */}
      <Dialog open={!!action} onOpenChange={closeDialog}>
        <DialogContent className="max-w-sm" dir={isAr ? 'rtl' : 'ltr'}>
          <DialogHeader>
            <DialogTitle>
              {action === 'assign'    && (isAr ? 'تعيين فني' : 'Assign Technician')}
              {action === 'warehouse' && (isAr ? 'قرار المستودع' : 'Warehouse Decision')}
              {action === 'approval'  && (isAr ? 'قرار الموافقة' : 'Approval Decision')}
              {action === 'close'     && (isAr ? 'إغلاق البلاغ' : 'Close Ticket')}
              {action === 'reopen'    && (isAr ? 'إعادة فتح البلاغ' : 'Reopen Ticket')}
              {action === 'transition' && (isAr ? `تحويل إلى: ${statusLabel[targetStatus] || targetStatus}` : `Transition to: ${statusLabel[targetStatus] || targetStatus}`)}
            </DialogTitle>
          </DialogHeader>

          <form onSubmit={handleSubmit} className="space-y-4">
            {error && (
              <p className="text-sm text-destructive bg-destructive/10 px-3 py-2 rounded-md">{error}</p>
            )}

            {/* Assign technician */}
            {action === 'assign' && (
              <div className="space-y-1.5">
                <Label>{tr.selectTechnician}</Label>
                <select
                  required
                  value={technicianId}
                  onChange={e => setTechnicianId(e.target.value)}
                  className={inputClass}
                >
                  <option value="">{isAr ? 'اختر فنياً' : 'Select technician'}</option>
                  {technicians.map(t => (
                    <option key={t.id} value={t.id}>{t.fullName}</option>
                  ))}
                </select>
              </div>
            )}

            {/* Warehouse decision */}
            {action === 'warehouse' && (
              <>
                <div className="space-y-1.5">
                  <Label>{isAr ? 'هل المواد متوفرة؟' : 'Are materials available?'}</Label>
                  <div className="flex gap-3">
                    <button type="button"
                      onClick={() => setMaterialsAvailable(true)}
                      className={`flex-1 py-2 rounded-md border text-sm font-medium transition-colors ${
                        materialsAvailable === true
                          ? 'bg-green-600 text-white border-green-600'
                          : 'border-border hover:bg-accent'
                      }`}
                    >
                      {isAr ? 'نعم، متوفرة' : 'Yes, available'}
                    </button>
                    <button type="button"
                      onClick={() => setMaterialsAvailable(false)}
                      className={`flex-1 py-2 rounded-md border text-sm font-medium transition-colors ${
                        materialsAvailable === false
                          ? 'bg-red-600 text-white border-red-600'
                          : 'border-border hover:bg-accent'
                      }`}
                    >
                      {isAr ? 'لا، غير متوفرة' : 'No, not available'}
                    </button>
                  </div>
                </div>
                <div className="space-y-1.5">
                  <Label>{tr.notes}</Label>
                  <textarea
                    rows={3}
                    value={warehouseNotes}
                    onChange={e => setWarehouseNotes(e.target.value)}
                    className={inputClass}
                    placeholder={isAr ? 'ملاحظات...' : 'Notes...'}
                  />
                </div>
              </>
            )}

            {/* Approval — decision already set, just need note */}
            {action === 'approval' && (
              <>
                <div className={`flex items-center gap-2 px-3 py-2 rounded-md text-sm font-medium ${
                  approvalDecision === 'approved' || approvalDecision === 'purchase_request'
                    ? 'bg-green-50 text-green-800 border border-green-200'
                    : 'bg-red-50 text-red-800 border border-red-200'
                }`}>
                  {approvalDecision === 'approved'         && <>{isAr ? '✅ موافقة' : '✅ Approve'}</>}
                  {approvalDecision === 'purchase_request' && <>{isAr ? '🛒 طلب شراء' : '🛒 Purchase Request'}</>}
                  {approvalDecision === 'rejected'         && <>{isAr ? '❌ رفض' : '❌ Reject'}</>}
                </div>
                <div className="space-y-1.5">
                  <Label>{tr.notes} {approvalDecision === 'rejected' && '*'}</Label>
                  <textarea
                    rows={3}
                    required={approvalDecision === 'rejected'}
                    value={note}
                    onChange={e => setNote(e.target.value)}
                    className={inputClass}
                    placeholder={isAr ? 'أضف ملاحظة...' : 'Add note...'}
                  />
                </div>
              </>
            )}

            {/* Transition — show date picker for in_progress */}
            {action === 'transition' && (
              <div className="space-y-1.5">
                <Label>{tr.notes}</Label>
                <textarea
                  rows={3}
                  value={note}
                  onChange={e => setNote(e.target.value)}
                  className={inputClass}
                  placeholder={isAr ? 'ملاحظات اختيارية' : 'Optional notes'}
                />
              </div>
            )}

            {/* Close / Reopen */}
            {(action === 'close' || action === 'reopen') && (
              <div className="space-y-1.5">
                <Label>{tr.notes} {action === 'reopen' && '*'}</Label>
                <textarea
                  rows={3}
                  required={action === 'reopen'}
                  value={note}
                  onChange={e => setNote(e.target.value)}
                  className={inputClass}
                  placeholder={isAr ? 'أضف ملاحظة...' : 'Add note...'}
                />
              </div>
            )}

            <div className="flex justify-end gap-3 pt-1">
              <Button type="button" variant="outline" onClick={closeDialog}>{tr.cancel}</Button>
              <Button
                type="submit"
                disabled={submitting || (action === 'warehouse' && materialsAvailable === null)}
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
