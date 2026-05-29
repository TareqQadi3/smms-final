'use client';

import { useEffect, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import DashboardLayout from '@/components/layout/DashboardLayout';
import { useAuthStore } from '@/store/authStore';
import { useT, statusColors, priorityColors } from '@/lib/i18n';
import { workOrderApi } from '@/lib/api';
import { WorkOrder, TicketHistory } from '@/lib/types';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { ArrowRight, Clock, User, CheckCircle, XCircle } from 'lucide-react';
import { format } from 'date-fns';

export default function WorkOrderDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { lang, user } = useAuthStore();
  const tr = useT(lang);
  const router = useRouter();
  const isAr = lang === 'ar';

  const [wo, setWo] = useState<WorkOrder | null>(null);
  const [history, setHistory] = useState<TicketHistory[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const load = async () => {
      try {
        const [woRes, histRes] = await Promise.all([
          workOrderApi.getById(id),
          workOrderApi.getHistory(id),
        ]);
        setWo(woRes.data);
        setHistory(histRes.data || []);
      } catch { /* silent */ }
      finally { setLoading(false); }
    };
    load();
  }, [id]);

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

  if (loading) {
    return (
      <DashboardLayout>
        <div className="flex items-center justify-center h-64">
          <div className="w-10 h-10 border-4 border-blue-600 border-t-transparent rounded-full animate-spin" />
        </div>
      </DashboardLayout>
    );
  }

  if (!wo) {
    return (
      <DashboardLayout>
        <div className="text-center py-20 text-gray-400">{tr.noData}</div>
      </DashboardLayout>
    );
  }

  return (
    <DashboardLayout>
      <div className="max-w-4xl mx-auto space-y-5">
        {/* Back */}
        <Button variant="ghost" size="sm" onClick={() => router.back()} className="gap-1.5 text-gray-600">
          {isAr ? <ArrowRight size={16} /> : <ArrowRight size={16} className="rotate-180" />}
          {isAr ? 'رجوع' : 'Back'}
        </Button>

        {/* Header */}
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <p className="text-xs font-mono text-blue-600 font-semibold">{wo.ticketNumber}</p>
            <h1 className="text-xl font-bold text-gray-900 mt-1">{wo.title}</h1>
          </div>
          <div className="flex gap-2">
            <span className={`px-3 py-1 rounded-full text-sm font-medium ${statusColors[wo.status] || ''}`}>
              {statusLabel[wo.status] || wo.status}
            </span>
            <span className={`px-2 py-1 rounded text-sm font-medium border ${priorityColors[wo.priority] || ''}`}>
              {wo.priority}
            </span>
          </div>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-5">
          {/* Details */}
          <div className="lg:col-span-2 space-y-5">
            <Card className="border-0 shadow-sm">
              <CardHeader className="pb-2"><CardTitle className="text-base">{isAr ? 'التفاصيل' : 'Details'}</CardTitle></CardHeader>
              <CardContent className="space-y-3">
                {wo.description && (
                  <div>
                    <p className="text-xs text-gray-500 font-medium mb-1">{tr.description}</p>
                    <p className="text-sm text-gray-800 bg-gray-50 p-3 rounded-lg">{wo.description}</p>
                  </div>
                )}
                <div className="grid grid-cols-2 gap-4 text-sm">
                  <div>
                    <p className="text-xs text-gray-500 font-medium">{tr.subType}</p>
                    <p className="font-medium capitalize">{wo.subType}</p>
                  </div>
                  {wo.locationDetail && (
                    <div>
                      <p className="text-xs text-gray-500 font-medium">{tr.location}</p>
                      <p className="font-medium">{wo.locationDetail}</p>
                    </div>
                  )}
                  <div>
                    <p className="text-xs text-gray-500 font-medium">{tr.requestedBy}</p>
                    <p className="font-medium">{wo.requestedBy?.fullName || '-'}</p>
                  </div>
                  <div>
                    <p className="text-xs text-gray-500 font-medium">{tr.assignedTo}</p>
                    <p className="font-medium">{wo.assignedTo?.fullName || '-'}</p>
                  </div>
                  {wo.createdAt && (
                    <div>
                      <p className="text-xs text-gray-500 font-medium">{isAr ? 'تاريخ الإنشاء' : 'Created'}</p>
                      <p className="font-medium">{format(new Date(wo.createdAt), 'dd/MM/yyyy HH:mm')}</p>
                    </div>
                  )}
                  {wo.slaDeadline && (
                    <div>
                      <p className="text-xs text-gray-500 font-medium">{tr.slaDeadline}</p>
                      <p className={`font-medium ${wo.slaBreached ? 'text-red-600' : ''}`}>
                        {format(new Date(wo.slaDeadline), 'dd/MM/yyyy HH:mm')}
                        {wo.slaBreached && ' ⚠'}
                      </p>
                    </div>
                  )}
                </div>
              </CardContent>
            </Card>

            {/* Timeline */}
            <Card className="border-0 shadow-sm">
              <CardHeader className="pb-2"><CardTitle className="text-base">{tr.timeline}</CardTitle></CardHeader>
              <CardContent>
                {history.length === 0 ? (
                  <p className="text-gray-400 text-sm text-center py-4">{tr.noData}</p>
                ) : (
                  <div className="space-y-1">
                    {history.map((h, i) => (
                      <div key={h.id} className="flex gap-3">
                        <div className="flex flex-col items-center">
                          <div className={`w-8 h-8 rounded-full flex items-center justify-center shrink-0 ${
                            h.toStatus === 'completed' || h.toStatus === 'closed' ? 'bg-green-100 text-green-600' :
                            h.toStatus === 'cancelled' ? 'bg-red-100 text-red-600' : 'bg-blue-100 text-blue-600'
                          }`}>
                            {h.toStatus === 'completed' || h.toStatus === 'closed' ? <CheckCircle size={16} /> :
                             h.toStatus === 'cancelled' ? <XCircle size={16} /> : <Clock size={16} />}
                          </div>
                          {i < history.length - 1 && <div className="w-0.5 h-full bg-gray-200 my-1" />}
                        </div>
                        <div className="pb-4 flex-1">
                          <div className="flex flex-wrap items-center gap-2">
                            <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${statusColors[h.toStatus] || 'bg-gray-100'}`}>
                              {statusLabel[h.toStatus] || h.toStatus}
                            </span>
                            {h.fromStatus && (
                              <span className="text-xs text-gray-400">{isAr ? 'من:' : 'from:'} {statusLabel[h.fromStatus] || h.fromStatus}</span>
                            )}
                          </div>
                          {h.note && <p className="text-sm text-gray-700 mt-1">{h.note}</p>}
                          <div className="flex gap-3 mt-1 text-xs text-gray-400">
                            {h.actor && <span className="flex items-center gap-1"><User size={11} />{h.actor.fullName}</span>}
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
            <Card className="border-0 shadow-sm">
              <CardHeader className="pb-2"><CardTitle className="text-sm">{isAr ? 'معلومات الموقع' : 'Location Info'}</CardTitle></CardHeader>
              <CardContent className="space-y-2 text-sm">
                {wo.center && <div><p className="text-xs text-gray-500">{tr.center}</p><p className="font-medium">{wo.center.name}</p></div>}
                {wo.controlRoom && <div><p className="text-xs text-gray-500">{tr.controlRoom}</p><p className="font-medium">{wo.controlRoom.name}</p></div>}
              </CardContent>
            </Card>
          </div>
        </div>
      </div>
    </DashboardLayout>
  );
}
