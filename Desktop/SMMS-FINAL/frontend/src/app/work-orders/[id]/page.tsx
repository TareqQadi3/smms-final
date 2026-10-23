'use client';

import { useEffect, useState, useCallback } from 'react';
import { useParams, useRouter } from 'next/navigation';
import DashboardLayout from '@/components/layout/DashboardLayout';
import { useAuthStore } from '@/store/authStore';
import { useT, statusColors, priorityColors, tStatus, tPriority } from '@/lib/i18n';
import { workOrderApi } from '@/lib/api';
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
import { formatDate } from '@/lib/utils';

type ActionType =
  | 'transition'
  | 'assign'
  | 'warehouse'
  | 'approval'
  | 'close'
  | 'reopen'
  | 'requestInfo'
  | 'provideInfo';

interface Technician { id: string; fullName: string; }

// currentStage is returned by the API but is not part of the shared WorkOrder type
type WorkOrderWithStage = WorkOrder & { currentStage?: string };

export default function WorkOrderDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { lang, user } = useAuthStore();
  const tr = useT(lang);
  const router = useRouter();
  const isAr = lang === 'ar';

  const [wo, setWo] = useState<WorkOrderWithStage | null>(null);
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

    if (type === 'assign' && wo?.id) {
      const res = await workOrderApi.getAssignableTechnicians(wo.id).catch(() => null);
      setTechnicians(Array.isArray(res?.data) ? res.data : []);
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
    // Mirrors WorkflowEngine (backend is authoritative). assigned is reached only through
    // /assign, so it is never offered here; approval, warehouse, reopen and close have their own
    // buttons or dialogs. DEF-007: technician waiting_materials->in_progress and
    // maintenance_manager in_progress->completed are NOT engine moves and were removed.
    const map: Record<string, Record<string, string[]>> = {
      control_room: {
        new:         ['under_review', 'cancelled'],
        under_review: ['cancelled'],
        assigned:    ['cancelled'],
        in_progress: ['cancelled'],
        completed:   ['closed'],
      },
      technician: {
        assigned:    ['in_progress'],
        in_progress: ['waiting_materials', 'completed'],
      },
      maintenance_manager: {
        new:         ['cancelled'],
        under_review: ['cancelled'],
        assigned:    ['cancelled'],
        in_progress: ['cancelled'],
        completed:   ['closed'],
      },
      hospital_supervisor: {
        new: ['cancelled'],
      },
      system_admin: {
        new:               ['under_review', 'cancelled'],
        under_review:      ['cancelled'],
        assigned:          ['in_progress', 'cancelled'],
        in_progress:       ['completed', 'waiting_materials', 'cancelled'],
        waiting_materials: ['waiting_approval', 'in_progress'],
        waiting_approval:  ['in_progress', 'waiting_materials'],
        completed:         ['closed'],
      },
    };
    return map[role]?.[status] || [];
  };

  const infoPending   = wo?.currentStage === 'info_requested';
  // Same roles as the backend @PreAuthorize on /assign; blocked while information is pending
  const canAssign     = wo && ['control_room', 'maintenance_manager', 'system_admin'].includes(user?.role || '')
                        && wo.status === 'under_review' && !infoPending;
  const canRequestInfo = wo && ['control_room', 'maintenance_manager'].includes(user?.role || '')
                        && wo.status === 'under_review' && !infoPending;
  const canProvideInfo = wo && infoPending && wo.status === 'under_review'
                        && !!user?.id && wo.requestedBy?.id === user.id;
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
            {infoPending && (
              <span className="px-2 py-1 rounded text-sm font-medium bg-amber-100 text-amber-800 border border-amber-300">
                {isAr ? 'بانتظار معلومات من مقدم البلاغ' : 'Awaiting requester information'}
              </span>
            )}
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
                      <p className="font-medium">{formatDate(wo.createdAt, lang, true)}</p>
                    </div>
                  )}
                  {wo.slaDeadline && (
                    <div>
                      <p className="text-xs text-muted-foreground font-medium">{tr.slaDeadline}</p>
                      <p className={`font-medium ${wo.slaBreached ? 'text-red-600' : ''}`}>
                        {formatDate(wo.slaDeadline, lang, true)}
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
                            <span>{formatDate(h.createdAt, lang, true, '')}</span>
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
            {(transitions.length > 0 || canAssign || canRequestInfo || canProvideInfo || canWarehouse || canApproval || canReopen) && (
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

                  {/* Return for information */}
                  {canRequestInfo && (
                    <Button
                      className="w-full gap-2 justify-start"
                      size="sm"
                      variant="outline"
                      onClick={() => openAction('requestInfo')}
                    >
                      <RotateCcw size={15} />
                      {isAr ? 'طلب معلومات إضافية' : 'Request Information'}
                    </Button>
                  )}
                  {canProvideInfo && (
                    <Button
                      className="w-full gap-2 justify-start"
                      size="sm"
                      onClick={() => openAction('provideInfo')}
                    >
                      <CheckCircle size={15} />
                      {isAr ? 'تقديم المعلومات المطلوبة' : 'Provide Information'}
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
              {action === 'requestInfo' && (isAr ? 'طلب معلومات إضافية' : 'Request Information')}
              {action === 'provideInfo' && (isAr ? 'تقديم المعلومات' : 'Provide Information')}
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

            {/* Return for information: note is required */}
            {(action === 'requestInfo' || action === 'provideInfo') && (
              <div className="space-y-1.5">
                <Label>{tr.notes} *</Label>
                <textarea
                  rows={3}
                  required
                  maxLength={1000}
                  value={note}
                  onChange={e => setNote(e.target.value)}
                  className={inputClass}
                  placeholder={action === 'requestInfo'
                    ? (isAr ? 'ما المعلومات المطلوبة من مقدم البلاغ؟' : 'What do you need from the requester?')
                    : (isAr ? 'اكتب المعلومات المطلوبة...' : 'Write the requested information...')}
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
