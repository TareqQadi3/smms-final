'use client';

import { useEffect, useState } from 'react';
import DashboardLayout from '@/components/layout/DashboardLayout';
import { useAuthStore } from '@/store/authStore';
import { useT, statusColors, tStatus } from '@/lib/i18n';
import { workOrderApi, controlRoomApi, maintenanceTypeApi } from '@/lib/api';
import { WorkOrder, TicketPriority } from '@/lib/types';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { CheckCircle, Plus } from 'lucide-react';
import { formatDate } from '@/lib/utils';
import { StateMessage } from '@/components/ui/state-message';

type CR = { id: string; name: string; nameAr?: string };
type MT = { id: string; name: string; nameAr: string; controlRoomType: string };
const PRIORITIES: TicketPriority[] = ['critical','high','medium','low'];
// Backend SubType enum values keyed by maintenance-type display name (English, lowercased).
// Types whose name is not listed fall back to name.toLowerCase().replace(/ /g,'_'), which
// matches the remaining enum values (printer, network, equipment_failure, ...).
const SUBTYPE_BY_NAME: Record<string, string> = {
  'electrical': 'electrical',
  'plumbing': 'plumbing',
  'hvac': 'hvac',
  'civil works': 'civil',
};
const toSubTypeValue = (name: string) =>
  SUBTYPE_BY_NAME[name.trim().toLowerCase()] ?? name.trim().toLowerCase().replace(/\s+/g, '_');
const SEL = "w-full h-10 rounded-md border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring";

export default function NewRequestPage() {
  const { lang, user } = useAuthStore();
  const tr   = useT(lang);
  const isAr = lang === 'ar';

  const [myOrders,     setMyOrders]     = useState<WorkOrder[]>([]);
  const [controlRooms, setControlRooms] = useState<CR[]>([]);
  const [maintTypes,   setMaintTypes]   = useState<MT[]>([]);
  const [crLoading,    setCrLoading]    = useState(false);
  const [mtLoading,    setMtLoading]    = useState(false);
  const [loadError,    setLoadError]    = useState(false);

  const [form, setForm] = useState({
    controlRoomId: '', subType: '', title: '',
    description: '', locationDetail: '', priority: 'medium' as TicketPriority,
  });
  const [submitting, setSubmitting] = useState(false);
  const [success,    setSuccess]    = useState(false);
  const [err,        setErr]        = useState('');

  /* load control rooms scoped to user's center */
  useEffect(() => {
    const load = async () => {
      setCrLoading(true);
      setLoadError(false);
      try {
        const [ordersRes, crRes] = await Promise.all([
          workOrderApi.getAll({ page: 0, size: 10 }),
          controlRoomApi.getAll(), // backend enforces center scoping (ISSUE 2 FIX)
        ]);
        setMyOrders(ordersRes.data.content || []);
        setControlRooms(crRes.data || []);
      } catch { setLoadError(true); }
      finally { setCrLoading(false); }
    };
    load();
  }, []);

  /* ISSUE 3 FIX: load maintenance types filtered by selected control room */
  const handleCRChange = async (crId: string) => {
    setForm(f => ({ ...f, controlRoomId: crId, subType: '' }));
    setMaintTypes([]);
    if (!crId) return;
    setMtLoading(true);
    try {
      const res = await maintenanceTypeApi.getByControlRoom(crId);
      setMaintTypes(res.data || []);
    } catch { /* silent */ }
    finally { setMtLoading(false); }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErr('');
    setSubmitting(true);
    try {
      await workOrderApi.create({
        controlRoomId: form.controlRoomId,
        subType:       form.subType,     // already an enum value (see toSubTypeValue)
        title:         form.title,
        description:   form.description,
        locationDetail:form.locationDetail,
        priority:      form.priority,
      });
      setSuccess(true);
      setForm({ controlRoomId:'', subType:'', title:'', description:'', locationDetail:'', priority:'medium' });
      setMaintTypes([]);
      const res = await workOrderApi.getAll({ page: 0, size: 10 });
      setMyOrders(res.data.content || []);
      setTimeout(() => setSuccess(false), 4000);
    } catch (ex: unknown) {
      const msg = (ex as { response?: { data?: { message?: string } } })?.response?.data?.message;
      setErr(msg ?? (isAr ? 'حدث خطأ، حاول مرة أخرى' : 'An error occurred, please try again'));
    }
    finally { setSubmitting(false); }
  };

  const statusLabel: Record<string, string> = {
    new:          isAr ? 'جديد'          : 'New',
    under_review: isAr ? 'قيد المراجعة' : 'Under Review',
    assigned:     isAr ? 'مُعيَّن'       : 'Assigned',
    in_progress:  isAr ? 'قيد التنفيذ'  : 'In Progress',
    completed:    isAr ? 'مكتمل'         : 'Completed',
    closed:       isAr ? 'مغلق'          : 'Closed',
  };

  return (
    <DashboardLayout>
      <div className="max-w-4xl mx-auto space-y-6">
        <div>
          <h1 className="text-2xl font-bold text-foreground">{isAr ? 'طلب صيانة جديد' : 'New Maintenance Request'}</h1>
          <p className="text-sm text-muted-foreground">{isAr ? 'أرسل بلاغ صيانة جديد' : 'Submit a new maintenance request'}</p>
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
                {err && (
                  <div className="mb-4 bg-destructive/10 text-destructive px-4 py-3 rounded-lg text-sm">
                    {err}
                  </div>
                )}
                <form onSubmit={handleSubmit} className="space-y-4">

                  {/* Control Room — scoped to user's center by backend */}
                  <div className="space-y-1.5">
                    <Label>{isAr ? 'غرفة التحكم' : 'Control Room'} *</Label>
                    {crLoading ? (
                      <p className="text-sm text-muted-foreground">{isAr ? 'جاري التحميل...' : 'Loading...'}</p>
                    ) : (
                      <select required className={SEL} value={form.controlRoomId}
                        onChange={e => handleCRChange(e.target.value)}>
                        <option value="">{isAr ? '— اختر غرفة التحكم —' : '— Select Control Room —'}</option>
                        {controlRooms.map(cr => (
                          <option key={cr.id} value={cr.id}>
                            {isAr ? cr.nameAr || cr.name : cr.name}
                          </option>
                        ))}
                      </select>
                    )}
                  </div>

                  {/* Maintenance Type — ISSUE 3 FIX: filtered by selected CR */}
                  <div className="grid grid-cols-2 gap-4">
                    <div className="space-y-1.5">
                      <Label>{isAr ? 'نوع الصيانة' : 'Maintenance Type'} *</Label>
                      {!form.controlRoomId ? (
                        <p className="text-xs text-muted-foreground py-2">
                          {isAr ? 'اختر غرفة التحكم أولاً' : 'Select a control room first'}
                        </p>
                      ) : mtLoading ? (
                        <p className="text-sm text-muted-foreground">{isAr ? 'جاري التحميل...' : 'Loading...'}</p>
                      ) : maintTypes.length === 0 ? (
                        <p className="text-xs text-destructive py-2">
                          {isAr ? 'لا توجد أنواع صيانة لهذه الغرفة' : 'No maintenance types for this control room'}
                        </p>
                      ) : (
                        <select required className={SEL} value={form.subType}
                          onChange={e => setForm(f => ({ ...f, subType: e.target.value }))}>
                          <option value="">{isAr ? '— اختر النوع —' : '— Select Type —'}</option>
                          {maintTypes.map(mt => (
                            <option key={mt.id} value={toSubTypeValue(mt.name)}>
                              {isAr ? mt.nameAr || mt.name : mt.name}
                            </option>
                          ))}
                        </select>
                      )}
                    </div>
                    <div className="space-y-1.5">
                      <Label>{tr.priority}</Label>
                      <select className={SEL} value={form.priority}
                        onChange={e => setForm(f => ({ ...f, priority: e.target.value as TicketPriority }))}>
                        {PRIORITIES.map(p => (
                          <option key={p} value={p}>{p.charAt(0).toUpperCase() + p.slice(1)}</option>
                        ))}
                      </select>
                    </div>
                  </div>

                  <div className="space-y-1.5">
                    <Label>{tr.title} *</Label>
                    <Input required value={form.title}
                      onChange={e => setForm(f => ({ ...f, title: e.target.value }))}
                      placeholder={isAr ? 'اكتب عنوان البلاغ' : 'Enter request title'} />
                  </div>
                  <div className="space-y-1.5">
                    <Label>{tr.description}</Label>
                    <Textarea rows={4} value={form.description}
                      onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
                      placeholder={isAr ? 'اشرح المشكلة بالتفصيل' : 'Describe the issue in detail'} />
                  </div>
                  <div className="space-y-1.5">
                    <Label>{isAr ? 'الموقع / الغرفة' : 'Location / Room'}</Label>
                    <Input value={form.locationDetail}
                      onChange={e => setForm(f => ({ ...f, locationDetail: e.target.value }))}
                      placeholder={isAr ? 'مثال: الطابق الثالث، غرفة 305' : 'e.g., Floor 3, Room 305'} />
                  </div>

                  <Button type="submit"
                    disabled={submitting || !form.controlRoomId || !form.title || !form.subType}
                    className="w-full bg-primary hover:bg-primary/90 h-11">
                    {submitting
                      ? (isAr ? 'جاري الإرسال...' : 'Submitting...')
                      : (isAr ? 'إرسال البلاغ' : 'Submit Request')}
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
                {loadError ? (
                  <StateMessage kind="error">
                    {isAr ? 'تعذّر تحميل بلاغاتك وغرف التحكم. حدّث الصفحة وأعد المحاولة.' : 'Unable to load your requests and control rooms. Refresh the page and try again.'}
                  </StateMessage>
                ) : myOrders.length === 0 ? (
                  <StateMessage>{isAr ? 'لم ترسل أي بلاغ بعد.' : 'You have not submitted any requests yet.'}</StateMessage>
                ) : (
                  <div className="divide-y">
                    {myOrders.map(wo => (
                      <div key={wo.id} className="px-4 py-3">
                        <div className="flex items-center justify-between mb-1">
                          <span className="font-mono text-xs text-blue-600 whitespace-nowrap tabular-nums"><bdi dir="ltr">{wo.ticketNumber}</bdi></span>
                          <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${statusColors[wo.status] || ''}`}>
                            {tStatus(wo.status, lang)}
                          </span>
                        </div>
                        <p className="text-sm font-medium truncate">{wo.title}</p>
                        <p className="text-xs text-muted-foreground mt-0.5 tabular-nums">
                          {formatDate(wo.createdAt, lang, false, '')}
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
