'use client';
import { useEffect, useState, useCallback } from 'react';
import DashboardLayout from '@/components/layout/DashboardLayout';
import { useAuthStore } from '@/store/authStore';
import { maintenanceTypeApi } from '@/lib/api';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Plus, Pencil, Trash2, Settings } from 'lucide-react';
import { useRouter } from 'next/navigation';

type MT = { id: string; name: string; nameAr: string; controlRoomType: string; active: boolean };
type CRType = 'residential' | 'hospital' | 'it' | 'medical';

const CR_TYPE_LABELS: Record<CRType, { ar: string; en: string; color: string }> = {
  residential: { ar: 'سكني',     en: 'Residential', color: 'bg-blue-100 text-blue-800'   },
  hospital:    { ar: 'مستشفى',   en: 'Hospital',    color: 'bg-green-100 text-green-800'  },
  it:          { ar: 'تقنية',    en: 'IT',          color: 'bg-purple-100 text-purple-800' },
  medical:     { ar: 'طبي',      en: 'Medical',     color: 'bg-orange-100 text-orange-800' },
};
const SEL = "w-full h-10 rounded-md border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring";

function extractMsg(err: unknown, isAr: boolean): string {
  const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
  return msg ?? (isAr ? 'حدث خطأ' : 'An error occurred');
}

export default function ConfigPage() {
  const { lang, user } = useAuthStore();
  const router = useRouter();
  const isAr = lang === 'ar';

  // Guard: only system_admin
  useEffect(() => {
    if (user && user.role !== 'system_admin') router.replace('/dashboard');
  }, [user, router]);

  const [types,   setTypes]   = useState<MT[]>([]);
  const [loading, setLoading] = useState(true);
  const [filter,  setFilter]  = useState<string>('');

  /* create */
  const [createOpen, setCreateOpen] = useState(false);
  const [createForm, setCreateForm] = useState({ name:'', nameAr:'', controlRoomType:'residential' as CRType });
  const [createErr,  setCreateErr]  = useState('');
  const [creating,   setCreating]   = useState(false);

  /* edit */
  const [editOpen,   setEditOpen]   = useState(false);
  const [editTarget, setEditTarget] = useState<MT | null>(null);
  const [editForm,   setEditForm]   = useState({ name:'', nameAr:'', controlRoomType:'residential' as CRType });
  const [editErr,    setEditErr]    = useState('');
  const [editing,    setEditing]    = useState(false);

  /* delete confirm */
  const [deleteOpen,   setDeleteOpen]   = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<MT | null>(null);
  const [deleting,     setDeleting]     = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await maintenanceTypeApi.getAll();
      setTypes(res.data ?? []);
    } catch { /* silent */ }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { load(); }, [load]);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setCreateErr('');
    setCreating(true);
    try {
      await maintenanceTypeApi.create(createForm);
      setCreateOpen(false);
      load();
    } catch (err) { setCreateErr(extractMsg(err, isAr)); }
    finally { setCreating(false); }
  };

  const openEdit = (mt: MT) => {
    setEditTarget(mt);
    setEditForm({ name: mt.name, nameAr: mt.nameAr, controlRoomType: mt.controlRoomType as CRType });
    setEditErr('');
    setEditOpen(true);
  };

  const handleEdit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editTarget) return;
    setEditErr('');
    setEditing(true);
    try {
      await maintenanceTypeApi.update(editTarget.id, editForm);
      setEditOpen(false);
      load();
    } catch (err) { setEditErr(extractMsg(err, isAr)); }
    finally { setEditing(false); }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await maintenanceTypeApi.delete(deleteTarget.id);
      setDeleteOpen(false);
      load();
    } catch { /* silent */ }
    finally { setDeleting(false); }
  };

  const filtered = types.filter(t =>
    !filter ||
    t.name.toLowerCase().includes(filter.toLowerCase()) ||
    t.nameAr.includes(filter)
  );

  const grouped = (Object.keys(CR_TYPE_LABELS) as CRType[]).reduce<Record<CRType, MT[]>>(
    (acc, key) => { acc[key] = filtered.filter(t => t.controlRoomType === key); return acc; },
    {} as Record<CRType, MT[]>
  );

  if (!user || user.role !== 'system_admin') return null;

  return (
    <DashboardLayout>
      <div className="max-w-5xl mx-auto space-y-6">
        {/* Header */}
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold flex items-center gap-2">
              <Settings size={22} className="text-primary"/>
              {isAr ? 'إعدادات النظام' : 'System Configuration'}
            </h1>
            <p className="text-sm text-muted-foreground">
              {isAr ? 'إدارة أنواع الصيانة وإعدادات النظام' : 'Manage maintenance types and system settings'}
            </p>
          </div>
          <Button onClick={() => { setCreateForm({ name:'', nameAr:'', controlRoomType:'residential' }); setCreateErr(''); setCreateOpen(true); }}
            className="gap-2 bg-primary hover:bg-primary/90">
            <Plus size={16}/> {isAr ? 'نوع صيانة جديد' : 'New Maintenance Type'}
          </Button>
        </div>

        {/* Search */}
        <Input
          placeholder={isAr ? 'بحث...' : 'Search...'}
          value={filter}
          onChange={e => setFilter(e.target.value)}
          className="max-w-sm"
        />

        {/* Grouped by CR Type */}
        {loading ? (
          <div className="flex justify-center h-48 items-center">
            <div className="w-8 h-8 border-4 border-primary border-t-transparent rounded-full animate-spin"/>
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
            {(Object.entries(grouped) as [CRType, MT[]][]).map(([crType, items]) => (
              <Card key={crType} className="border-0 shadow-sm">
                <CardHeader className="pb-2">
                  <CardTitle className="text-sm flex items-center gap-2">
                    <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${CR_TYPE_LABELS[crType].color}`}>
                      {isAr ? CR_TYPE_LABELS[crType].ar : CR_TYPE_LABELS[crType].en}
                    </span>
                    <span className="text-muted-foreground font-normal">
                      {items.length} {isAr ? 'نوع' : 'types'}
                    </span>
                  </CardTitle>
                </CardHeader>
                <CardContent className="p-0">
                  {items.length === 0 ? (
                    <p className="text-center text-muted-foreground/60 py-6 text-sm">
                      {isAr ? 'لا توجد أنواع' : 'No types'}
                    </p>
                  ) : (
                    <div className="divide-y">
                      {items.map(mt => (
                        <div key={mt.id} className="px-4 py-3 flex items-center justify-between hover:bg-muted/30">
                          <div>
                            <p className="text-sm font-medium">{mt.name}</p>
                            <p className="text-xs text-muted-foreground">{mt.nameAr}</p>
                          </div>
                          <div className="flex items-center gap-1">
                            <button onClick={() => openEdit(mt)}
                              className="p-1.5 rounded hover:bg-blue-50 hover:text-blue-600 text-muted-foreground transition-colors">
                              <Pencil size={14}/>
                            </button>
                            <button onClick={() => { setDeleteTarget(mt); setDeleteOpen(true); }}
                              className="p-1.5 rounded hover:bg-red-50 hover:text-red-600 text-muted-foreground transition-colors">
                              <Trash2 size={14}/>
                            </button>
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </CardContent>
              </Card>
            ))}
          </div>
        )}
      </div>

      {/* ══ CREATE DIALOG ══ */}
      <Dialog open={createOpen} onOpenChange={setCreateOpen}>
        <DialogContent dir={isAr?'rtl':'ltr'} className="max-w-sm">
          <DialogHeader>
            <DialogTitle>{isAr ? 'إضافة نوع صيانة' : 'Add Maintenance Type'}</DialogTitle>
          </DialogHeader>
          <form onSubmit={handleCreate} className="space-y-4">
            <div className="space-y-1.5">
              <Label>{isAr ? 'الاسم (إنجليزي)' : 'Name (English)'} *</Label>
              <Input required value={createForm.name}
                onChange={e => setCreateForm(f => ({ ...f, name: e.target.value }))}
                placeholder="e.g. Electrical"/>
            </div>
            <div className="space-y-1.5">
              <Label>{isAr ? 'الاسم (عربي)' : 'Name (Arabic)'} *</Label>
              <Input required dir="rtl" value={createForm.nameAr}
                onChange={e => setCreateForm(f => ({ ...f, nameAr: e.target.value }))}
                placeholder="مثال: كهرباء"/>
            </div>
            <div className="space-y-1.5">
              <Label>{isAr ? 'نوع غرفة التحكم' : 'Control Room Type'} *</Label>
              <select className={SEL} value={createForm.controlRoomType}
                onChange={e => setCreateForm(f => ({ ...f, controlRoomType: e.target.value as CRType }))}>
                {(Object.entries(CR_TYPE_LABELS) as [CRType, { ar: string; en: string }][]).map(([v, l]) => (
                  <option key={v} value={v}>{isAr ? l.ar : l.en}</option>
                ))}
              </select>
            </div>
            {createErr && <p className="text-sm text-destructive">{createErr}</p>}
            <div className="flex justify-end gap-3">
              <Button type="button" variant="outline" onClick={() => setCreateOpen(false)}>
                {isAr ? 'إلغاء' : 'Cancel'}
              </Button>
              <Button type="submit" disabled={creating} className="min-w-[80px]">
                {creating ? <span className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin inline-block"/> : (isAr ? 'حفظ' : 'Save')}
              </Button>
            </div>
          </form>
        </DialogContent>
      </Dialog>

      {/* ══ EDIT DIALOG ══ */}
      <Dialog open={editOpen} onOpenChange={setEditOpen}>
        <DialogContent dir={isAr?'rtl':'ltr'} className="max-w-sm">
          <DialogHeader>
            <DialogTitle>{isAr ? 'تعديل نوع الصيانة' : 'Edit Maintenance Type'}</DialogTitle>
          </DialogHeader>
          <form onSubmit={handleEdit} className="space-y-4">
            <div className="space-y-1.5">
              <Label>{isAr ? 'الاسم (إنجليزي)' : 'Name (English)'} *</Label>
              <Input required value={editForm.name}
                onChange={e => setEditForm(f => ({ ...f, name: e.target.value }))}/>
            </div>
            <div className="space-y-1.5">
              <Label>{isAr ? 'الاسم (عربي)' : 'Name (Arabic)'} *</Label>
              <Input required dir="rtl" value={editForm.nameAr}
                onChange={e => setEditForm(f => ({ ...f, nameAr: e.target.value }))}/>
            </div>
            <div className="space-y-1.5">
              <Label>{isAr ? 'نوع غرفة التحكم' : 'Control Room Type'} *</Label>
              <select className={SEL} value={editForm.controlRoomType}
                onChange={e => setEditForm(f => ({ ...f, controlRoomType: e.target.value as CRType }))}>
                {(Object.entries(CR_TYPE_LABELS) as [CRType, { ar: string; en: string }][]).map(([v, l]) => (
                  <option key={v} value={v}>{isAr ? l.ar : l.en}</option>
                ))}
              </select>
            </div>
            {editErr && <p className="text-sm text-destructive">{editErr}</p>}
            <div className="flex justify-end gap-3">
              <Button type="button" variant="outline" onClick={() => setEditOpen(false)}>
                {isAr ? 'إلغاء' : 'Cancel'}
              </Button>
              <Button type="submit" disabled={editing} className="min-w-[80px]">
                {editing ? <span className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin inline-block"/> : (isAr ? 'حفظ' : 'Save')}
              </Button>
            </div>
          </form>
        </DialogContent>
      </Dialog>

      {/* ══ DELETE CONFIRM ══ */}
      <Dialog open={deleteOpen} onOpenChange={setDeleteOpen}>
        <DialogContent dir={isAr?'rtl':'ltr'} className="max-w-sm">
          <DialogHeader>
            <DialogTitle>{isAr ? 'حذف نوع الصيانة' : 'Delete Maintenance Type'}</DialogTitle>
          </DialogHeader>
          <p className="text-sm text-muted-foreground py-2">
            {isAr
              ? `هل تريد حذف "${deleteTarget?.nameAr}"؟ لن يظهر في النظام بعد الآن.`
              : `Delete "${deleteTarget?.name}"? It will no longer appear in the system.`}
          </p>
          <div className="flex justify-end gap-3">
            <Button variant="outline" onClick={() => setDeleteOpen(false)} disabled={deleting}>
              {isAr ? 'إلغاء' : 'Cancel'}
            </Button>
            <Button onClick={handleDelete} disabled={deleting}
              className="bg-destructive hover:bg-destructive/90 min-w-[80px]">
              {deleting ? <span className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin inline-block"/> : (isAr ? 'حذف' : 'Delete')}
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    </DashboardLayout>
  );
}
