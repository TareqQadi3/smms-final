'use client';
import { useEffect, useState, useCallback } from 'react';
import DashboardLayout from '@/components/layout/DashboardLayout';
import { useAuthStore } from '@/store/authStore';
import { regionApi } from '@/lib/api';
import { Region } from '@/lib/types';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { BilingualInput } from '@/components/ui/BilingualInput';
import { Plus, MapPin, Pencil } from 'lucide-react';

type Form = { nameAr: string; name: string; code: string };

export default function RegionsPage() {
  const { lang } = useAuthStore();
  const isAr = lang === 'ar';

  const [regions, setRegions] = useState<Region[]>([]);
  const [loading, setLoading] = useState(true);

  // create
  const [createOpen, setCreateOpen] = useState(false);
  const [createForm, setCreateForm] = useState<Form>({ nameAr: '', name: '', code: '' });
  const [creating,   setCreating]   = useState(false);
  const [createErr,  setCreateErr]  = useState('');

  // edit
  const [editOpen,   setEditOpen]   = useState(false);
  const [editForm,   setEditForm]   = useState<Form>({ nameAr: '', name: '', code: '' });
  const [editTarget, setEditTarget] = useState<Region | null>(null);
  const [editing,    setEditing]    = useState(false);
  const [editErr,    setEditErr]    = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    try { const r = await regionApi.getAll(); setRegions(r.data || []); }
    catch { /* silent */ } finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); }, [load]);

  /* ── create ── */
  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!createForm.nameAr.trim()) { setCreateErr(isAr ? 'الاسم العربي مطلوب' : 'Arabic name required'); return; }
    setCreating(true); setCreateErr('');
    try {
      await regionApi.create(createForm);
      setCreateOpen(false);
      setCreateForm({ nameAr: '', name: '', code: '' });
      load();
    } catch (err: unknown) {
      setCreateErr(extractMsg(err, isAr));
    } finally { setCreating(false); }
  };

  /* ── open edit ── */
  const openEdit = (r: Region) => {
    setEditTarget(r);
    setEditForm({ nameAr: r.nameAr, name: r.name, code: r.code });
    setEditErr('');
    setEditOpen(true);
  };

  /* ── edit submit ── */
  const handleEdit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editTarget) return;
    setEditing(true); setEditErr('');
    try {
      await regionApi.update(String(editTarget.id), editForm);
      setEditOpen(false);
      load();
    } catch (err: unknown) {
      setEditErr(extractMsg(err, isAr));
    } finally { setEditing(false); }
  };

  return (
    <DashboardLayout>
      <div className="max-w-4xl mx-auto space-y-5">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold">{isAr ? 'المناطق' : 'Regions'}</h1>
            <p className="text-sm text-muted-foreground">{regions.length} {isAr ? 'منطقة' : 'regions'}</p>
          </div>
          <Button onClick={() => { setCreateForm({ nameAr:'', name:'', code:'' }); setCreateErr(''); setCreateOpen(true); }}
            className="gap-2 bg-primary hover:bg-primary/90">
            <Plus size={16}/> {isAr ? 'إضافة منطقة' : 'Add Region'}
          </Button>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {loading ? Array.from({length:3}).map((_,i)=>(
            <Card key={i} className="border-0 shadow-sm"><CardContent className="p-5 h-24 animate-pulse bg-muted rounded-lg"/></Card>
          )) : regions.length === 0 ? (
            <div className="col-span-3 text-center py-16 text-muted-foreground">
              <MapPin size={40} className="mx-auto mb-3 opacity-30"/>
              <p>{isAr ? 'لا توجد مناطق بعد' : 'No regions yet'}</p>
            </div>
          ) : regions.map(r => (
            <Card key={r.id} className="border-0 shadow-sm hover:shadow-md transition-shadow">
              <CardContent className="p-5">
                <div className="flex items-start justify-between">
                  <div className="w-10 h-10 rounded-xl bg-primary/10 flex items-center justify-center text-primary">
                    <MapPin size={20}/>
                  </div>
                  <div className="flex items-center gap-2">
                    <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${r.active ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'}`}>
                      {r.active ? (isAr ? 'نشط':'Active') : (isAr ? 'غير نشط':'Inactive')}
                    </span>
                    <button onClick={() => openEdit(r)}
                      className="p-1 rounded hover:bg-blue-50 hover:text-blue-600 text-muted-foreground transition-colors"
                      title={isAr ? 'تعديل' : 'Edit'}>
                      <Pencil size={14}/>
                    </button>
                  </div>
                </div>
                <div className="mt-3">
                  <p className="font-bold text-base">{isAr ? r.nameAr : r.name}</p>
                  <p className="text-sm text-muted-foreground">{isAr ? r.name : r.nameAr}</p>
                  <p className="text-xs font-mono text-primary mt-1 font-semibold">{r.code}</p>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      </div>

      {/* ── Create Dialog ── */}
      <Dialog open={createOpen} onOpenChange={setCreateOpen}>
        <DialogContent dir={isAr ? 'rtl' : 'ltr'} className="max-w-lg">
          <DialogHeader><DialogTitle>{isAr ? 'إضافة منطقة جديدة' : 'Add New Region'}</DialogTitle></DialogHeader>
          <form onSubmit={handleCreate} className="space-y-4">
            <BilingualInput
              labelAr={isAr ? 'الاسم بالعربية' : 'Arabic Name'}
              labelEn={isAr ? 'الاسم بالإنجليزية' : 'English Name'}
              valueAr={createForm.nameAr} valueEn={createForm.name}
              onChangeAr={v => setCreateForm(f=>({...f, nameAr:v}))}
              onChangeEn={v => setCreateForm(f=>({...f, name:v}))}
              placeholderAr="تجمع جازان الصحي" placeholderEn="Jazan Health Cluster"
              required
            />
            <div className="space-y-1.5">
              <Label>{isAr ? 'الرمز' : 'Code'} *</Label>
              <Input required value={createForm.code}
                onChange={e=>setCreateForm(f=>({...f,code:e.target.value.toUpperCase()}))}
                placeholder="JHC" maxLength={20} dir="ltr"/>
            </div>
            {createErr && <p className="text-sm text-destructive bg-destructive/10 px-3 py-2 rounded">{createErr}</p>}
            <div className="flex justify-end gap-3">
              <Button type="button" variant="outline" onClick={()=>setCreateOpen(false)}>{isAr?'إلغاء':'Cancel'}</Button>
              <Button type="submit" disabled={creating} className="bg-primary hover:bg-primary/90 min-w-[80px]">
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
            <DialogTitle>{isAr ? 'تعديل المنطقة' : 'Edit Region'}
              <span className="text-muted-foreground font-normal text-sm ms-2">— {editTarget?.code}</span>
            </DialogTitle>
          </DialogHeader>
          <form onSubmit={handleEdit} className="space-y-4">
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

function Spinner() { return <span className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin inline-block"/>; }
function extractMsg(err: unknown, isAr: boolean) {
  const m = (err as {response?:{data?:{message?:string}}})?.response?.data?.message;
  return m ?? (isAr ? 'حدث خطأ' : 'An error occurred');
}
