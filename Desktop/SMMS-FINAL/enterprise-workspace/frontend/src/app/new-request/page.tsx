'use client';

import { useEffect, useState } from 'react';
import DashboardLayout from '@/components/layout/DashboardLayout';
import { useAuthStore } from '@/store/authStore';
import { useT, statusColors } from '@/lib/i18n';
import { workOrderApi, controlRoomApi } from '@/lib/api';
import { WorkOrder, TicketPriority } from '@/lib/types';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { CheckCircle, Plus } from 'lucide-react';
import { format } from 'date-fns';

const SUB_TYPES = ['electrical','plumbing','hvac','civil','printer','network','software','devices','equipment_failure','preventive_maintenance','calibration'];
const PRIORITIES: TicketPriority[] = ['critical','high','medium','low'];

export default function NewRequestPage() {
  const { lang, user } = useAuthStore();
  const tr = useT(lang);
  const isAr = lang === 'ar';

  const [myOrders, setMyOrders] = useState<WorkOrder[]>([]);
  const [controlRooms, setControlRooms] = useState<{id:string;name:string}[]>([]);
  const [form, setForm] = useState({ controlRoomId:'', subType:'', title:'', description:'', locationDetail:'', priority:'medium' as TicketPriority });
  const [submitting, setSubmitting] = useState(false);
  const [success, setSuccess] = useState(false);

  useEffect(() => {
    const load = async () => {
      try {
        const [ordersRes, crRes] = await Promise.all([
          workOrderApi.getAll({ page: 0, size: 10 }),
          controlRoomApi.getAll(),
        ]);
        setMyOrders(ordersRes.data.content || []);
        setControlRooms(crRes.data || []);
      } catch { /* silent */ }
    };
    load();
  }, []);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    try {
      await workOrderApi.create(form);
      setSuccess(true);
      setForm({ controlRoomId:'', subType:'', title:'', description:'', locationDetail:'', priority:'medium' });
      const res = await workOrderApi.getAll({ page: 0, size: 10 });
      setMyOrders(res.data.content || []);
      setTimeout(() => setSuccess(false), 4000);
    } catch { /* silent */ }
    finally { setSubmitting(false); }
  };

  const statusLabel: Record<string, string> = {
    new: isAr ? 'جديد' : 'New',
    under_review: isAr ? 'قيد المراجعة' : 'Under Review',
    assigned: isAr ? 'مُعيَّن' : 'Assigned',
    in_progress: isAr ? 'قيد التنفيذ' : 'In Progress',
    completed: isAr ? 'مكتمل' : 'Completed',
    closed: isAr ? 'مغلق' : 'Closed',
  };

  return (
    <DashboardLayout>
      <div className="max-w-4xl mx-auto space-y-6">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">{isAr ? 'طلب صيانة جديد' : 'New Maintenance Request'}</h1>
          <p className="text-sm text-gray-500">{isAr ? 'أرسل بلاغ صيانة جديد' : 'Submit a new maintenance request'}</p>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-5 gap-6">
          {/* Form */}
          <div className="lg:col-span-3">
            <Card className="border-0 shadow-sm">
              <CardHeader className="pb-2">
                <CardTitle className="text-base flex items-center gap-2">
                  <Plus size={18} className="text-blue-600" />
                  {isAr ? 'تفاصيل البلاغ' : 'Request Details'}
                </CardTitle>
              </CardHeader>
              <CardContent>
                {success && (
                  <div className="mb-4 bg-green-50 border border-green-200 text-green-700 px-4 py-3 rounded-lg flex items-center gap-2">
                    <CheckCircle size={16} />
                    {isAr ? 'تم إرسال البلاغ بنجاح!' : 'Request submitted successfully!'}
                  </div>
                )}
                <form onSubmit={handleSubmit} className="space-y-4">
                  <div className="space-y-1.5">
                    <Label>{isAr ? 'غرفة التحكم' : 'Control Room'} *</Label>
                    <Select required value={form.controlRoomId} onValueChange={v => setForm(f=>({...f,controlRoomId:v}))}>
                      <SelectTrigger><SelectValue placeholder={isAr ? 'اختر غرفة التحكم' : 'Select control room'} /></SelectTrigger>
                      <SelectContent>
                        {controlRooms.map(cr => <SelectItem key={cr.id} value={cr.id}>{cr.name}</SelectItem>)}
                      </SelectContent>
                    </Select>
                  </div>
                  <div className="grid grid-cols-2 gap-4">
                    <div className="space-y-1.5">
                      <Label>{isAr ? 'نوع الصيانة' : 'Maintenance Type'} *</Label>
                      <Select required value={form.subType} onValueChange={v => setForm(f=>({...f,subType:v}))}>
                        <SelectTrigger><SelectValue placeholder={isAr ? 'اختر النوع' : 'Select type'} /></SelectTrigger>
                        <SelectContent>
                          {SUB_TYPES.map(s => <SelectItem key={s} value={s} className="capitalize">{s.replace('_',' ')}</SelectItem>)}
                        </SelectContent>
                      </Select>
                    </div>
                    <div className="space-y-1.5">
                      <Label>{tr.priority}</Label>
                      <Select value={form.priority} onValueChange={v => setForm(f=>({...f,priority:v as TicketPriority}))}>
                        <SelectTrigger><SelectValue /></SelectTrigger>
                        <SelectContent>
                          {PRIORITIES.map(p => <SelectItem key={p} value={p} className="capitalize">{p}</SelectItem>)}
                        </SelectContent>
                      </Select>
                    </div>
                  </div>
                  <div className="space-y-1.5">
                    <Label>{tr.title} *</Label>
                    <Input required value={form.title} onChange={e => setForm(f=>({...f,title:e.target.value}))} placeholder={isAr ? 'اكتب عنوان البلاغ' : 'Enter request title'} />
                  </div>
                  <div className="space-y-1.5">
                    <Label>{tr.description}</Label>
                    <Textarea rows={4} value={form.description} onChange={e => setForm(f=>({...f,description:e.target.value}))} placeholder={isAr ? 'اشرح المشكلة بالتفصيل' : 'Describe the issue in detail'} />
                  </div>
                  <div className="space-y-1.5">
                    <Label>{isAr ? 'الموقع / الغرفة' : 'Location / Room'}</Label>
                    <Input value={form.locationDetail} onChange={e => setForm(f=>({...f,locationDetail:e.target.value}))} placeholder={isAr ? 'مثال: الطابق الثالث، غرفة 305' : 'e.g., Floor 3, Room 305'} />
                  </div>
                  <Button type="submit" disabled={submitting || !form.controlRoomId || !form.title || !form.subType} className="w-full bg-[#0f2744] hover:bg-[#1a3a6b] h-11">
                    {submitting ? (isAr ? 'جاري الإرسال...' : 'Submitting...') : (isAr ? 'إرسال البلاغ' : 'Submit Request')}
                  </Button>
                </form>
              </CardContent>
            </Card>
          </div>

          {/* My Requests */}
          <div className="lg:col-span-2">
            <Card className="border-0 shadow-sm">
              <CardHeader className="pb-2">
                <CardTitle className="text-base">{isAr ? 'بلاغاتي' : 'My Requests'}</CardTitle>
              </CardHeader>
              <CardContent className="p-0">
                {myOrders.length === 0 ? (
                  <p className="text-center text-gray-400 py-8 text-sm">{tr.noData}</p>
                ) : (
                  <div className="divide-y">
                    {myOrders.map(wo => (
                      <div key={wo.id} className="px-4 py-3">
                        <div className="flex items-center justify-between mb-1">
                          <span className="font-mono text-xs text-blue-600">{wo.ticketNumber}</span>
                          <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${statusColors[wo.status] || ''}`}>
                            {statusLabel[wo.status] || wo.status}
                          </span>
                        </div>
                        <p className="text-sm font-medium truncate">{wo.title}</p>
                        <p className="text-xs text-gray-400 mt-0.5">
                          {wo.createdAt ? format(new Date(wo.createdAt), 'dd/MM/yyyy') : ''}
                        </p>
                      </div>
                    ))}
                  </div>
                )}
              </CardContent>
            </Card>
          </div>
        </div>
      </div>
    </DashboardLayout>
  );
}
