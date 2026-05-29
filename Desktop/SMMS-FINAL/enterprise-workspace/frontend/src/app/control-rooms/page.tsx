'use client';
import { useEffect, useState, useCallback } from 'react';
import DashboardLayout from '@/components/layout/DashboardLayout';
import { useAuthStore } from '@/store/authStore';
import { controlRoomApi, centerApi } from '@/lib/api';
import { ControlRoom, Center } from '@/lib/types';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { BilingualInput } from '@/components/ui/BilingualInput';
import { Plus, Radio, Pencil } from 'lucide-react';

const CR_TYPES = [
  { value: 'residential', labelAr: 'سكني',               labelEn: 'Residential' },
  { value: 'hospital',    labelAr: 'مستشفى / إداري',      labelEn: 'Hospital / Admin' },
  { value: 'it',          labelAr: 'تقنية المعلومات',     labelEn: 'IT' },
  { value: 'medical',     labelAr: 'طبي',                 labelEn: 'Medical' },
];

const SEL = "w-full h-10 rounded-md border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring";

type Form = { centerId: string; nameAr: string; name: string; code: string; type: string };

export default function ControlRoomsPage() {
  const { lang } = useAuthStore();
  const isAr = lang === 'ar';

  const [rooms,   setRooms]   = useState<ControlRoom[]>([]);
  const [centers, setCenters] = useState<Center[]>([]);
  const [loading, setLoading] = useState(true);

  // create
  const [createOpen, setCreateOpen] = useState(false);
  const [createForm, setCreateForm] = useState<Form>(emptyForm());
  const [creating,   setCreating]   = useState(false);
  const [createErr,  setCreateErr]  = useState('');

  // edit
  const [editOpen,   setEditOpen]   = useState(false);
  const [editForm,   setEditForm]   = useState<Form>(emptyForm());
  const [editTarget, setEditTarget] = useState<ControlRoom | null>(null);
  const [editing,    setEditing]    = useState(false);
  const [editErr,    setEditErr]    = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [rRes, cRes] = await Promise.all([controlRoomApi.getAll(), centerApi.getAll()]);
      setRooms(rRes.data || []);
      setCenters(cRes.data || []);
    } catch { /* silent */ } finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); }, [load]);

  const typeLabel = (t: string) => CR_TYPES.find(x=>x.value===t)?.[isAr?'labelAr':'labelEn'] ?? t;

  /* ── create ── */
  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!createForm.centerId) { setCreateErr(isAr ? 'اختر المركز' : 'Select a center'); return; }
    if (!createForm.type)     { setCreateErr(isAr ? 'اختر النوع'  : 'Select type');    return; }
    setCreating(true); setCreateErr('');
    try {
      await controlRoomApi.create(createForm);
      setCreateOpen(false); setCreateForm(emptyForm()); load();
    } catch (err: unknown) { setCreateErr(extractMsg(err, isAr)); }
    finally { setCreating(false); }
  };

  /* ── open edit ── */
  const openEdit = (r: ControlRoom) => {
    setEditTarget(r);
    setEditForm({ centerId: r.center?.id ?? '', nameAr: r.nameAr, name: r.name, code: r.code ?? '', type: r.type });
    setEditErr(''); setEditOpen(true);
  };

  /* ── edit submit ── */
  const handleEdit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editTarget) return;
    setEditing(true); setEditErr('');
    try {
      await controlRoomApi.update(String(editTarget.id), editForm);
      setEditOpen(false); load();
    } catch (err: unknown) { setEditErr(extractMsg(err, isAr)); }
    finally { setEditing(false); }
  };

  return (
    <DashboardLayout>
      <div className="max-w-5xl mx-auto space-y-5">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold">{isAr ? 'غرف التحكم' : 'Control Rooms'}</h1>
            <p className="text-sm text-muted-foreground">{rooms.length} {isAr ? 'غرفة' : 'rooms'}</p>
          </div>
          <Button onClick={() => { setCreateForm(emptyForm()); setCreateErr(''); setCreateOpen(true); }}
            className="gap-2 bg-primary hover:bg-primary/90">
            <Plus size={16}/> {isAr ? 'إضافة غرفة' : 'Add Room'}
          </Button>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {loading ? Array.from({length:3}).map((_,i)=>(
            <Card key={i} className="border-0 shadow-sm"><CardContent className="p-5 h-28 animate-pulse bg-muted rounded-lg"/></Card>
          )) : rooms.length === 0 ? (
            <div className="col-span-3 text-center py-16 text-muted-foreground">
              <Radio size={40} className="mx-auto mb-3 opacity-30"/>
              <p>{isAr ? 'لا توجد غرف تحكم بعد' : 'No control rooms yet'}</p>
            </div>
          ) : rooms.map(r => (
            <Card key={r.id} className="border-0 shadow-sm hover:shadow-md transition-shadow">
              <CardContent className="p-5">
                <div className="flex items-start justify-between mb-3">
                  <div className="w-10 h-10 rounded-xl bg-primary/10 flex items-center justify-center text-primary">
                    <Radio size={20}/>
                  </div>
                  <div className="flex items-center gap-2">
                    <span className="text-xs bg-primary/10 text-primary px-2 py-0.5 rounded font-medium">
                      {typeLabel(r.type)}
                    </span>
                    <button onClick={() => openEdit(r)}
                      className="p-1 rounded hover:bg-blue-50 hover:text-blue-600 text-muted-foreground transition-colors"
                      title={isAr ? 'تعديل' : 'Edit'}>
                      <Pencil size={14}/>
                    </button>
                  </div>
                </div>
                <p className="font-bold">{isAr ? r.nameAr : r.name}</p>
                {r.code && <p className="text-xs font-mono text-primary/70 mt-0.5">{r.code}</p>}
                {r.center && (
                  <p className="text-xs text-muted-foreground mt-1">
                    🏥 {isAr ? r.center.nameAr : r.center.name}
                  </p>
                )}
              </CardContent>
            </Card>
          ))}
        </div>
      </div>

      {/* ── Create Dialog ── */}
      <Dialog open={createOpen} onOpenChange={setCreateOpen}>
        <DialogContent dir={isAr ? 'rtl' : 'ltr'} className="max-w-lg">
          <DialogHeader><DialogTitle>{isAr ? 'إضافة غرفة تحكم' : 'Add Control Room'}</DialogTitle></DialogHeader>
          <form onSubmit={handleCreate} className="space-y-4">
            <div className="space-y-1.5">
              <Label>{isAr ? 'المركز' : 'Center'} *</Label>
              {centers.length === 0 ? (
                <p className="text-sm text-destructive bg-destructive/10 px-3 py-2 rounded">
                  {isAr ? 'أضف مركزاً أولاً' : 'Add a center first'}
                </p>
              ) : (
                <select className={SEL} value={createForm.centerId}
                  onChange={e => setCreateForm(f=>({...f, centerId: e.target.value}))}>
                  <option value="">{isAr ? '— اختر المركز —' : '— Select Center —'}</option>
                  {centers.map(c => <option key={c.id} value={c.id}>{isAr ? c.nameAr : c.name}</option>)}
                </select>
              )}
            </div>
            <div className="space-y-1.5">
              <Label>{isAr ? 'النوع' : 'Type'} *</Label>
              <select className={SEL} value={createForm.type}
                onChange={e => setCreateForm(f=>({...f, type: e.target.value}))}>
                <option value="">{isAr ? '— اختر النوع —' : '— Select Type —'}</option>
                {CR_TYPES.map(t => <option key={t.value} value={t.value}>{isAr ? t.labelAr : t.labelEn}</option>)}
              </select>
            </div>
            <BilingualInput
              labelAr={isAr ? 'الاسم بالعربية' : 'Arabic Name'}
              labelEn={isAr ? 'الاسم بالإنجليزية' : 'English Name'}
              valueAr={createForm.nameAr} valueEn={createForm.name}
              onChangeAr={v => setCreateForm(f=>({...f, nameAr:v}))}
              onChangeEn={v => setCreateForm(f=>({...f, name:v}))}
              placeholderAr="غرفة التحكم الرئيسية" placeholderEn="Main Control Room"
              required
            />
            <div className="space-y-1.5">
              <Label>{isAr ? 'الرمز' : 'Code'} *</Label>
              <Input required value={createForm.code}
                onChange={e=>setCreateForm(f=>({...f,code:e.target.value.toUpperCase()}))}
                placeholder="MCR-01" maxLength={20} dir="ltr"/>
            </div>
            {createErr && <p className="text-sm text-destructive bg-destructive/10 px-3 py-2 rounded">{createErr}</p>}
            <div className="flex justify-end gap-3">
              <Button type="button" variant="outline" onClick={()=>setCreateOpen(false)}>{isAr?'إلغاء':'Cancel'}</Button>
              <Button type="submit" disabled={creating || centers.length===0} className="bg-primary hover:bg-primary/90 min-w-[80px]">
                {creating ? <Spinner/> : (isAr?'حفظ':'Save')}
              </Button>
            </div>
          </form>
        </DialogContent>
      </Dialog>

      {/* ── Edit Dialog ── */}
      <Dialog open={editOpen} onOpenChange={setEditOpen}>
        <DialogContent dir={isAr ? 'rtl' : 'ltr'} className="max-w-lg">
          <DialogHeader>
            <DialogTitle>{isAr ? 'تعديل غرفة التحكم' : 'Edit Control Room'}
              <span className="text-muted-foreground font-normal text-sm ms-2">— {editTarget?.code}</span>
            </DialogTitle>
          </DialogHeader>
          <form onSubmit={handleEdit} className="space-y-4">
            <div className="space-y-1.5">
              <Label>{isAr ? 'المركز' : 'Center'}</Label>
              <select className={SEL} value={editForm.centerId}
                onChange={e => setEditForm(f=>({...f, centerId: e.target.value}))}>
                <option value="">{isAr ? '— بدون تغيير —' : '— No Change —'}</option>
                {centers.map(c => <option key={c.id} value={c.id}>{isAr ? c.nameAr : c.name}</option>)}
              </select>
            </div>
            <div className="space-y-1.5">
              <Label>{isAr ? 'النوع' : 'Type'}</Label>
              <select className={SEL} value={editForm.type}
                onChange={e => setEditForm(f=>({...f, type: e.target.value}))}>
                <option value="">{isAr ? '— بدون تغيير —' : '— No Change —'}</option>
                {CR_TYPES.map(t => <option key={t.value} value={t.value}>{isAr ? t.labelAr : t.labelEn}</option>)}
              </select>
            </div>
            <BilingualInput
              labelAr={isAr ? 'الاسم بالعربية' : 'Arabic Name'}
              labelEn={isAr ? 'الاسم بالإنجليزية' : 'English Name'}
              valueAr={editForm.nameAr} valueEn={editForm.name}
              onChangeAr={v => setEditForm(f=>({...f, nameAr:v}))}
              onChangeEn={v => setEditForm(f=>({...f, name:v}))}
              required
            />
            <div className="space-y-1.5">
              <Label>{isAr ? 'الرمز' : 'Code'}</Label>
              <Input value={editForm.code}
                onChange={e=>setEditForm(f=>({...f,code:e.target.value.toUpperCase()}))}
                maxLength={20} dir="ltr"/>
            </div>
            {editErr && <p className="text-sm text-destructive bg-destructive/10 px-3 py-2 rounded">{editErr}</p>}
            <div className="flex justify-end gap-3">
              <Button type="button" variant="outline" onClick={()=>setEditOpen(false)}>{isAr?'إلغاء':'Cancel'}</Button>
              <Button type="submit" disabled={editing} className="bg-primary hover:bg-primary/90 min-w-[80px]">
                {editing ? <Spinner/> : (isAr?'حفظ':'Save')}
              </Button>
            </div>
          </form>
        </DialogContent>
      </Dialog>
    </DashboardLayout>
  );
}

function emptyForm(): Form { return { centerId:'', nameAr:'', name:'', code:'', type:'' }; }
function Spinner() { return <span className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin inline-block"/>; }
function extractMsg(err: unknown, isAr: boolean) {
  const m = (err as {response?:{data?:{message?:string}}})?.response?.data?.message;
  return m ?? (isAr ? 'حدث خطأ' : 'An error occurred');
}
