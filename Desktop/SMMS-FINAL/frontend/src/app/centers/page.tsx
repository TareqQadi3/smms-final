'use client';
import { useEffect, useState, useCallback } from 'react';
import DashboardLayout from '@/components/layout/DashboardLayout';
import { useAuthStore } from '@/store/authStore';
import { centerApi, regionApi } from '@/lib/api';
import { Center, Region } from '@/lib/types';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { BilingualInput } from '@/components/ui/BilingualInput';
import { Plus, Building2, Pencil } from 'lucide-react';

const SEL = "w-full h-10 rounded-md border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring";

type Form = { regionId: string; nameAr: string; name: string; code: string; address: string };

export default function CentersPage() {
  const { lang } = useAuthStore();
  const isAr = lang === 'ar';

  const [centers, setCenters] = useState<Center[]>([]);
  const [regions, setRegions] = useState<Region[]>([]);
  const [loading, setLoading] = useState(true);

  // create
  const [createOpen, setCreateOpen] = useState(false);
  const [createForm, setCreateForm] = useState<Form>(emptyForm());
  const [creating,   setCreating]   = useState(false);
  const [createErr,  setCreateErr]  = useState('');

  // edit
  const [editOpen,   setEditOpen]   = useState(false);
  const [editForm,   setEditForm]   = useState<Form>(emptyForm());
  const [editTarget, setEditTarget] = useState<Center | null>(null);
  const [editing,    setEditing]    = useState(false);
  const [editErr,    setEditErr]    = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [cRes, rRes] = await Promise.all([centerApi.getAll(), regionApi.getAll()]);
      setCenters(cRes.data || []);
      setRegions(rRes.data || []);
    } catch { /* silent */ } finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); }, [load]);

  /* ── create ── */
  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!createForm.regionId) { setCreateErr(isAr ? 'اختر المنطقة' : 'Select a region'); return; }
    setCreating(true); setCreateErr('');
    try {
      await centerApi.create(createForm);
      setCreateOpen(false); setCreateForm(emptyForm()); load();
    } catch (err: unknown) { setCreateErr(extractMsg(err, isAr)); }
    finally { setCreating(false); }
  };

  /* ── open edit ── */
  const openEdit = (c: Center) => {
    setEditTarget(c);
    setEditForm({ regionId: c.region?.id ?? '', nameAr: c.nameAr, name: c.name, code: c.code, address: c.address ?? '' });
    setEditErr(''); setEditOpen(true);
  };

  /* ── edit submit ── */
  const handleEdit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editTarget) return;
    setEditing(true); setEditErr('');
    try {
      await centerApi.update(String(editTarget.id), editForm);
      setEditOpen(false); load();
    } catch (err: unknown) { setEditErr(extractMsg(err, isAr)); }
    finally { setEditing(false); }
  };

  return (
    <DashboardLayout>
      <div className="max-w-5xl mx-auto space-y-5">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold">{isAr ? 'المراكز' : 'Centers'}</h1>
            <p className="text-sm text-muted-foreground">{centers.length} {isAr ? 'مركز' : 'centers'}</p>
          </div>
          <Button onClick={() => { setCreateForm(emptyForm()); setCreateErr(''); setCreateOpen(true); }}
            className="gap-2 bg-primary hover:bg-primary/90">
            <Plus size={16}/> {isAr ? 'إضافة مركز' : 'Add Center'}
          </Button>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {loading ? Array.from({length:3}).map((_,i)=>(
            <Card key={i} className="border-0 shadow-sm"><CardContent className="p-5 h-28 animate-pulse bg-muted rounded-lg"/></Card>
          )) : centers.length === 0 ? (
            <div className="col-span-3 text-center py-16 text-muted-foreground">
              <Building2 size={40} className="mx-auto mb-3 opacity-30"/>
              <p>{isAr ? 'لا توجد مراكز بعد' : 'No centers yet'}</p>
            </div>
          ) : centers.map(c => (
            <Card key={c.id} className="border-0 shadow-sm hover:shadow-md transition-shadow">
              <CardContent className="p-5">
                <div className="flex items-start justify-between mb-3">
                  <div className="w-10 h-10 rounded-xl bg-primary/10 flex items-center justify-center text-primary">
                    <Building2 size={20}/>
                  </div>
                  <div className="flex items-center gap-2">
                    <span className="text-xs font-mono text-primary font-bold bg-primary/10 px-2 py-0.5 rounded">{c.code}</span>
                    <button onClick={() => openEdit(c)}
                      className="p-1 rounded hover:bg-blue-50 hover:text-blue-600 text-muted-foreground transition-colors"
                      title={isAr ? 'تعديل' : 'Edit'}>
                      <Pencil size={14}/>
                    </button>
                  </div>
                </div>
                <p className="font-bold">{isAr ? c.nameAr : c.name}</p>
                <p className="text-sm text-muted-foreground">{isAr ? c.name : c.nameAr}</p>
                {c.region && (
                  <p className="text-xs text-muted-foreground mt-2 flex items-center gap-1">
                    📍 {isAr ? c.region.nameAr : c.region.name}
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
          <DialogHeader><DialogTitle>{isAr ? 'إضافة مركز جديد' : 'Add New Center'}</DialogTitle></DialogHeader>
          <form onSubmit={handleCreate} className="space-y-4">
            <div className="space-y-1.5">
              <Label>{isAr ? 'المنطقة' : 'Region'} *</Label>
              {regions.length === 0 ? (
                <p className="text-sm text-destructive bg-destructive/10 px-3 py-2 rounded">
                  {isAr ? 'أضف منطقة أولاً' : 'Add a region first'}
                </p>
              ) : (
                <select className={SEL} value={createForm.regionId}
                  onChange={e => setCreateForm(f=>({...f, regionId: e.target.value}))}>
                  <option value="">{isAr ? '— اختر المنطقة —' : '— Select Region —'}</option>
                  {regions.map(r => <option key={r.id} value={r.id}>{isAr ? r.nameAr : r.name}</option>)}
                </select>
              )}
            </div>
            <BilingualInput
              labelAr={isAr ? 'الاسم بالعربية' : 'Arabic Name'}
              labelEn={isAr ? 'الاسم بالإنجليزية' : 'English Name'}
              valueAr={createForm.nameAr} valueEn={createForm.name}
              onChangeAr={v => setCreateForm(f=>({...f, nameAr:v}))}
              onChangeEn={v => setCreateForm(f=>({...f, name:v}))}
              placeholderAr="مستشفى الملك فهد" placeholderEn="King Fahad Hospital"
              required
            />
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <Label>{isAr ? 'الرمز' : 'Code'} *</Label>
                <Input required value={createForm.code}
                  onChange={e=>setCreateForm(f=>({...f,code:e.target.value.toUpperCase()}))}
                  placeholder="KFH" maxLength={20} dir="ltr"/>
              </div>
              <div className="space-y-1.5">
                <Label>{isAr ? 'العنوان' : 'Address'}</Label>
                <Input value={createForm.address}
                  onChange={e=>setCreateForm(f=>({...f,address:e.target.value}))}
                  placeholder={isAr ? 'جازان، حي الروضة' : 'Jazan, Al-Rawdah'}/>
              </div>
            </div>
            {createErr && <p className="text-sm text-destructive bg-destructive/10 px-3 py-2 rounded">{createErr}</p>}
            <div className="flex justify-end gap-3">
              <Button type="button" variant="outline" onClick={()=>setCreateOpen(false)}>{isAr?'إلغاء':'Cancel'}</Button>
              <Button type="submit" disabled={creating || regions.length === 0} className="bg-primary hover:bg-primary/90 min-w-[80px]">
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
            <DialogTitle>{isAr ? 'تعديل المركز' : 'Edit Center'}
              <span className="text-muted-foreground font-normal text-sm ms-2">— {editTarget?.code}</span>
            </DialogTitle>
          </DialogHeader>
          <form onSubmit={handleEdit} className="space-y-4">
            <div className="space-y-1.5">
              <Label>{isAr ? 'المنطقة' : 'Region'}</Label>
              <select className={SEL} value={editForm.regionId}
                onChange={e => setEditForm(f=>({...f, regionId: e.target.value}))}>
                <option value="">{isAr ? '— بدون تغيير —' : '— No Change —'}</option>
                {regions.map(r => <option key={r.id} value={r.id}>{isAr ? r.nameAr : r.name}</option>)}
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
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <Label>{isAr ? 'الرمز' : 'Code'}</Label>
                <Input value={editForm.code}
                  onChange={e=>setEditForm(f=>({...f,code:e.target.value.toUpperCase()}))}
                  maxLength={20} dir="ltr"/>
              </div>
              <div className="space-y-1.5">
                <Label>{isAr ? 'العنوان' : 'Address'}</Label>
                <Input value={editForm.address}
                  onChange={e=>setEditForm(f=>({...f,address:e.target.value}))}/>
              </div>
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

function emptyForm(): Form { return { regionId:'', nameAr:'', name:'', code:'', address:'' }; }
function Spinner() { return <span className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin inline-block"/>; }
function extractMsg(err: unknown, isAr: boolean) {
  const m = (err as {response?:{data?:{message?:string}}})?.response?.data?.message;
  return m ?? (isAr ? 'حدث خطأ' : 'An error occurred');
}
