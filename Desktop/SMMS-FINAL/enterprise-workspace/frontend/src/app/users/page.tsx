'use client';
import { useEffect, useState, useCallback } from 'react';
import DashboardLayout from '@/components/layout/DashboardLayout';
import { useAuthStore } from '@/store/authStore';
import { useT, roleLabels } from '@/lib/i18n';
import { userApi, centerApi, regionApi, controlRoomApi } from '@/lib/api';
import { User, UserRole } from '@/lib/types';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Plus, Search, UserCheck, UserX, Pencil, Power, PowerOff } from 'lucide-react';

/* ─────────────────── constants ─────────────────── */
const ALL_ROLES: UserRole[] = [
  'system_admin','region_supervisor','hospital_supervisor','control_room',
  'technician','maintenance_manager','electrical_manager','warehouse',
  'operating_company_manager','resident','department',
];
const REGION_ROLES = new Set<UserRole>(['region_supervisor']);
const CENTER_ROLES = new Set<UserRole>([
  'hospital_supervisor','control_room','technician','maintenance_manager',
  'electrical_manager','warehouse','operating_company_manager','resident','department',
]);
const CONTROL_ROOM_ROLES = new Set<UserRole>(['control_room']);

const SEL = "w-full h-10 rounded-md border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring";

type DropItem = { id: string; name: string; nameAr: string };

/* ─────────────────── component ──────────────────── */
export default function UsersPage() {
  const { lang, user: me } = useAuthStore();
  const tr   = useT(lang);
  const isAr = lang === 'ar';
  const isAdmin = me?.role === 'system_admin';

  /* list state */
  const [users,   setUsers]   = useState<User[]>([]);
  const [loading, setLoading] = useState(true);
  const [search,  setSearch]  = useState('');

  /* dropdown data */
  const [centers,      setCenters]      = useState<DropItem[]>([]);
  const [regions,      setRegions]      = useState<DropItem[]>([]);
  const [controlRooms, setControlRooms] = useState<DropItem[]>([]);
  const [crLoading,    setCrLoading]    = useState(false);

  /* create dialog */
  const [createOpen, setCreateOpen] = useState(false);
  const [createForm, setCreateForm] = useState(emptyCreate());
  const [createErr,  setCreateErr]  = useState('');
  const [creating,   setCreating]   = useState(false);

  /* edit dialog */
  const [editOpen, setEditOpen] = useState(false);
  const [editForm, setEditForm] = useState(emptyEdit());
  const [editErr,  setEditErr]  = useState('');
  const [editing,  setEditing]  = useState(false);
  const [editTarget, setEditTarget] = useState<User | null>(null);

  /* confirm toggle-active dialog */
  const [confirmOpen,   setConfirmOpen]   = useState(false);
  const [confirmTarget, setConfirmTarget] = useState<User | null>(null);
  const [toggling,      setToggling]      = useState(false);

  /* ─── computed ─── */
  const needsRegion      = REGION_ROLES.has(createForm.role);
  const needsCenter      = CENTER_ROLES.has(createForm.role);
  const needsControlRoom = CONTROL_ROOM_ROLES.has(createForm.role);

  /* ─── load users ─── */
  const loadUsers = useCallback(async () => {
    setLoading(true);
    try {
      const r = await userApi.getAll();
      setUsers(r.data?.content ?? r.data ?? []);
    } catch { /* silent */ }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { loadUsers(); }, [loadUsers]);

  /* ─── open create dialog ─── */
  const openCreate = async () => {
    setCreateForm(emptyCreate());
    setCreateErr('');
    setControlRooms([]);
    try {
      const [cRes, rRes] = await Promise.all([centerApi.getAll(), regionApi.getAll()]);
      setCenters(cRes.data ?? []);
      setRegions(rRes.data ?? []);
    } catch { /* silent */ }
    setCreateOpen(true);
  };

  /* ─── when role changes: reset linked IDs ─── */
  const handleRoleChange = (role: UserRole) => {
    setCreateForm(f => ({ ...f, role, centerId: '', regionId: '', controlRoomId: '' }));
    setControlRooms([]);
  };

  /* ─── BUG 1 FIX: load control rooms filtered by center ─── */
  const handleCenterChange = async (centerId: string) => {
    setCreateForm(f => ({ ...f, centerId, controlRoomId: '' }));
    setControlRooms([]);
    if (!centerId || !needsControlRoom) return;
    setCrLoading(true);
    try {
      const res = await controlRoomApi.getAll(centerId); // ← passes centerId as query param
      setControlRooms(res.data ?? []);
    } catch { /* silent */ }
    finally { setCrLoading(false); }
  };

  /* ─── create submit ─── */
  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setCreateErr('');
    if (needsRegion      && !createForm.regionId)      { setCreateErr(isAr ? 'اختر المنطقة'     : 'Select a region');       return; }
    if (needsCenter      && !createForm.centerId)      { setCreateErr(isAr ? 'اختر المركز'      : 'Select a center');       return; }
    if (needsControlRoom && !createForm.controlRoomId) { setCreateErr(isAr ? 'اختر غرفة التحكم' : 'Select a control room'); return; }

    setCreating(true);
    try {
      const res = await userApi.create(createForm);
      const newId = res.data?.id ?? res.data?.userId;
      if (needsControlRoom && newId && createForm.controlRoomId) {
        try { await userApi.grantControlRoom(String(newId), createForm.controlRoomId); }
        catch { /* non-fatal */ }
      }
      setCreateOpen(false);
      loadUsers();
    } catch (err: unknown) {
      setCreateErr(extractMsg(err, isAr));
    } finally { setCreating(false); }
  };

  /* ─── BUG 2 FIX: open edit dialog pre-filled ─── */
  const openEdit = (u: User) => {
    setEditTarget(u);
    setEditForm({
      fullName:   u.fullName   ?? '',
      fullNameAr: u.fullNameAr ?? '',
      email:      u.email      ?? '',
      phone:      u.phone      ?? '',
    });
    setEditErr('');
    setEditOpen(true);
  };

  /* ─── edit submit ─── */
  const handleEdit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editTarget) return;
    setEditErr('');
    if (!editForm.fullName.trim()) { setEditErr(isAr ? 'الاسم مطلوب' : 'Name is required'); return; }
    setEditing(true);
    try {
      await userApi.update(String(editTarget.id), editForm);
      setEditOpen(false);
      loadUsers();
    } catch (err: unknown) {
      setEditErr(extractMsg(err, isAr));
    } finally { setEditing(false); }
  };

  /* ─── BUG 2 FIX: toggle active/inactive ─── */
  const openConfirm = (u: User) => { setConfirmTarget(u); setConfirmOpen(true); };

  const handleToggle = async () => {
    if (!confirmTarget) return;
    setToggling(true);
    try {
      await userApi.toggleActive(String(confirmTarget.id), !confirmTarget.active);
      setConfirmOpen(false);
      loadUsers();
    } catch { /* silent */ }
    finally { setToggling(false); }
  };

  /* ─── filtered list ─── */
  const filtered = users.filter(u =>
    !search ||
    u.fullName?.toLowerCase().includes(search.toLowerCase()) ||
    u.username?.toLowerCase().includes(search.toLowerCase()) ||
    u.email?.toLowerCase().includes(search.toLowerCase())
  );

  /* ─── render ─── */
  return (
    <DashboardLayout>
      <div className="max-w-6xl mx-auto space-y-5">

        {/* Header */}
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold">{tr.users}</h1>
            <p className="text-sm text-muted-foreground">{filtered.length} {isAr ? 'مستخدم' : 'users'}</p>
          </div>
          <Button onClick={openCreate} className="gap-2 bg-primary hover:bg-primary/90">
            <Plus size={16}/> {isAr ? 'مستخدم جديد' : 'New User'}
          </Button>
        </div>

        {/* Search */}
        <Card className="border-0 shadow-sm">
          <CardContent className="p-4">
            <div className="relative max-w-sm">
              <Search size={15} className={`absolute top-1/2 -translate-y-1/2 text-muted-foreground ${isAr ? 'right-3' : 'left-3'}`}/>
              <Input
                placeholder={tr.search}
                value={search}
                onChange={e => setSearch(e.target.value)}
                className={`${isAr ? 'pr-9' : 'pl-9'} h-9`}
              />
            </div>
          </CardContent>
        </Card>

        {/* Table */}
        <Card className="border-0 shadow-sm">
          <CardContent className="p-0">
            {loading ? (
              <div className="flex items-center justify-center h-48">
                <div className="w-8 h-8 border-4 border-primary border-t-transparent rounded-full animate-spin"/>
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="bg-muted/50 border-b">
                    <tr>
                      {[
                        tr.name, tr.username, tr.email, tr.role,
                        isAr ? 'المركز' : 'Center',
                        tr.status,
                        isAr ? 'الإجراءات' : 'Actions',
                      ].map(h => (
                        <th key={h} className="text-start px-4 py-3 font-medium text-muted-foreground whitespace-nowrap">{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {filtered.length === 0 ? (
                      <tr><td colSpan={7} className="text-center py-12 text-muted-foreground">{tr.noData}</td></tr>
                    ) : filtered.map(u => (
                      <tr key={u.id} className="border-b last:border-0 hover:bg-muted/30 transition-colors">

                        {/* Name */}
                        <td className="px-4 py-3">
                          <div className="flex items-center gap-3">
                            <div className="w-8 h-8 rounded-full bg-primary flex items-center justify-center text-primary-foreground text-xs font-bold shrink-0">
                              {u.fullName?.charAt(0) ?? 'U'}
                            </div>
                            <div>
                              <p className="font-medium">{u.fullName}</p>
                              {u.fullNameAr && <p className="text-xs text-muted-foreground">{u.fullNameAr}</p>}
                            </div>
                          </div>
                        </td>

                        {/* Username */}
                        <td className="px-4 py-3 text-muted-foreground font-mono text-xs">{u.username}</td>

                        {/* Email */}
                        <td className="px-4 py-3 text-muted-foreground text-xs max-w-[160px] truncate">{u.email || '—'}</td>

                        {/* Role */}
                        <td className="px-4 py-3">
                          <Badge variant="outline" className="text-xs whitespace-nowrap">
                            {roleLabels[u.role]?.[lang] ?? u.role}
                          </Badge>
                        </td>

                        {/* Center */}
                        <td className="px-4 py-3 text-muted-foreground text-xs">
                          {u.center ? (isAr ? u.center.nameAr ?? u.center.name : u.center.name) : '—'}
                        </td>

                        {/* Status */}
                        <td className="px-4 py-3">
                          {u.active
                            ? <span className="flex items-center gap-1 text-green-600 text-xs font-medium"><UserCheck size={13}/>{tr.active}</span>
                            : <span className="flex items-center gap-1 text-destructive text-xs font-medium"><UserX size={13}/>{tr.inactive}</span>}
                        </td>

                        {/* Actions — BUG 2 FIX */}
                        <td className="px-4 py-3">
                          <div className="flex items-center gap-1">
                            {/* Edit */}
                            <button
                              onClick={() => openEdit(u)}
                              title={isAr ? 'تعديل' : 'Edit'}
                              className="p-1.5 rounded hover:bg-blue-50 hover:text-blue-600 text-muted-foreground transition-colors"
                            >
                              <Pencil size={14}/>
                            </button>

                            {/* Toggle active — hide for own account */}
                            {String(me?.id) !== String(u.id) && (
                              <button
                                onClick={() => openConfirm(u)}
                                title={u.active
                                  ? (isAr ? 'تعطيل الحساب' : 'Deactivate')
                                  : (isAr ? 'تفعيل الحساب' : 'Activate')}
                                className={`p-1.5 rounded transition-colors ${
                                  u.active
                                    ? 'hover:bg-red-50 hover:text-red-600 text-muted-foreground'
                                    : 'hover:bg-green-50 hover:text-green-600 text-muted-foreground'
                                }`}
                              >
                                {u.active ? <PowerOff size={14}/> : <Power size={14}/>}
                              </button>
                            )}
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      {/* ══════════════ CREATE DIALOG ══════════════ */}
      <Dialog open={createOpen} onOpenChange={setCreateOpen}>
        <DialogContent dir={isAr ? 'rtl' : 'ltr'} className="max-w-lg max-h-[90vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>{isAr ? 'إنشاء مستخدم جديد' : 'Create New User'}</DialogTitle>
          </DialogHeader>
          <form onSubmit={handleCreate} className="space-y-4">

            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-1.5">
                <Label>{tr.fullName} *</Label>
                <Input
                  required
                  value={createForm.fullName}
                  onChange={e => setCreateForm(f => ({ ...f, fullName: e.target.value }))}
                  placeholder={isAr ? 'الاسم الكامل' : 'Full name'}
                />
              </div>
              <div className="space-y-1.5">
                <Label>{tr.username} *</Label>
                <Input
                  required
                  value={createForm.username}
                  onChange={e => setCreateForm(f => ({ ...f, username: e.target.value.trim() }))}
                  placeholder="user.name"
                />
              </div>
              <div className="space-y-1.5">
                <Label>{tr.email}</Label>
                <Input
                  type="email"
                  value={createForm.email}
                  onChange={e => setCreateForm(f => ({ ...f, email: e.target.value.trim() }))}
                  placeholder="email@domain.com"
                />
              </div>
              <div className="space-y-1.5">
                <Label>{tr.password} *</Label>
                <Input
                  required
                  type="password"
                  value={createForm.password}
                  onChange={e => setCreateForm(f => ({ ...f, password: e.target.value }))}
                  placeholder={isAr ? '8 أحرف على الأقل' : '8+ characters'}
                  minLength={8}
                />
              </div>
            </div>

            {/* Role */}
            <div className="space-y-1.5">
              <Label>{tr.role} *</Label>
              <select
                className={SEL}
                value={createForm.role}
                onChange={e => handleRoleChange(e.target.value as UserRole)}
              >
                {ALL_ROLES.map(r => (
                  <option key={r} value={r}>{roleLabels[r]?.[lang] ?? r}</option>
                ))}
              </select>
            </div>

            {/* Region (region_supervisor only) */}
            {needsRegion && (
              <div className="space-y-1.5">
                <Label className="text-amber-700 font-semibold">
                  {isAr ? '⚠ ربط بالمنطقة (مطلوب)' : '⚠ Link to Region (required)'}
                </Label>
                {regions.length === 0 ? (
                  <p className="text-sm bg-destructive/10 text-destructive px-3 py-2 rounded-md">
                    {isAr ? 'لا توجد مناطق — أضف منطقة أولاً' : 'No regions — add a region first'}
                  </p>
                ) : (
                  <select
                    className={SEL}
                    value={createForm.regionId}
                    onChange={e => setCreateForm(f => ({ ...f, regionId: e.target.value }))}
                  >
                    <option value="">{isAr ? '— اختر المنطقة —' : '— Select Region —'}</option>
                    {regions.map(r => (
                      <option key={r.id} value={r.id}>{isAr ? r.nameAr || r.name : r.name}</option>
                    ))}
                  </select>
                )}
              </div>
            )}

            {/* Center (most roles) */}
            {needsCenter && (
              <div className="space-y-1.5">
                <Label className="text-amber-700 font-semibold">
                  {isAr ? '⚠ ربط بالمركز (مطلوب)' : '⚠ Link to Center (required)'}
                </Label>
                {centers.length === 0 ? (
                  <p className="text-sm bg-destructive/10 text-destructive px-3 py-2 rounded-md">
                    {isAr ? 'لا توجد مراكز — أضف مركزاً أولاً' : 'No centers — add a center first'}
                  </p>
                ) : (
                  <select
                    className={SEL}
                    value={createForm.centerId}
                    onChange={e => handleCenterChange(e.target.value)}
                  >
                    <option value="">{isAr ? '— اختر المركز —' : '— Select Center —'}</option>
                    {centers.map(c => (
                      <option key={c.id} value={c.id}>{isAr ? c.nameAr || c.name : c.name}</option>
                    ))}
                  </select>
                )}
              </div>
            )}

            {/* Control Room — BUG 1 FIX: filtered by selected center */}
            {needsControlRoom && (
              <div className="space-y-1.5">
                <Label className="text-amber-700 font-semibold">
                  {isAr ? '⚠ ربط بغرفة تحكم (مطلوب)' : '⚠ Link to Control Room (required)'}
                </Label>
                {!createForm.centerId ? (
                  <p className="text-sm bg-blue-50 text-blue-700 px-3 py-2 rounded-md">
                    {isAr ? 'اختر المركز أولاً لتظهر غرف التحكم الخاصة به' : 'Select a center first to see its control rooms'}
                  </p>
                ) : crLoading ? (
                  <p className="text-sm text-muted-foreground px-3 py-2">{isAr ? 'جاري التحميل...' : 'Loading...'}</p>
                ) : controlRooms.length === 0 ? (
                  <p className="text-sm bg-destructive/10 text-destructive px-3 py-2 rounded-md">
                    {isAr ? 'لا توجد غرف تحكم لهذا المركز — أضف غرفة تحكم أولاً' : 'No control rooms for this center — add one first'}
                  </p>
                ) : (
                  <select
                    className={SEL}
                    value={createForm.controlRoomId}
                    onChange={e => setCreateForm(f => ({ ...f, controlRoomId: e.target.value }))}
                  >
                    <option value="">{isAr ? '— اختر غرفة التحكم —' : '— Select Control Room —'}</option>
                    {controlRooms.map(cr => (
                      <option key={cr.id} value={cr.id}>{isAr ? cr.nameAr || cr.name : cr.name}</option>
                    ))}
                  </select>
                )}
              </div>
            )}

            {createErr && (
              <p className="text-sm text-destructive bg-destructive/10 px-3 py-2 rounded-md">{createErr}</p>
            )}
            <div className="flex justify-end gap-3 pt-1">
              <Button type="button" variant="outline" onClick={() => setCreateOpen(false)}>{tr.cancel}</Button>
              <Button type="submit" disabled={creating} className="bg-primary hover:bg-primary/90 min-w-[80px]">
                {creating ? <span className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin inline-block"/> : tr.save}
              </Button>
            </div>
          </form>
        </DialogContent>
      </Dialog>

      {/* ══════════════ EDIT DIALOG ══════════════ */}
      <Dialog open={editOpen} onOpenChange={setEditOpen}>
        <DialogContent dir={isAr ? 'rtl' : 'ltr'} className="max-w-md">
          <DialogHeader>
            <DialogTitle>
              {isAr ? 'تعديل الحساب' : 'Edit Account'}
              {editTarget && <span className="text-muted-foreground font-normal text-sm ms-2">— {editTarget.username}</span>}
            </DialogTitle>
          </DialogHeader>
          <form onSubmit={handleEdit} className="space-y-4">
            <div className="space-y-1.5">
              <Label>{isAr ? 'الاسم الكامل (عربي)' : 'Full Name'} *</Label>
              <Input
                required
                value={editForm.fullName}
                onChange={e => setEditForm(f => ({ ...f, fullName: e.target.value }))}
              />
            </div>
            <div className="space-y-1.5">
              <Label>{isAr ? 'الاسم بالعربية' : 'Arabic Name'}</Label>
              <Input
                dir="rtl"
                value={editForm.fullNameAr}
                onChange={e => setEditForm(f => ({ ...f, fullNameAr: e.target.value }))}
              />
            </div>
            <div className="space-y-1.5">
              <Label>{tr.email}</Label>
              <Input
                type="email"
                value={editForm.email}
                onChange={e => setEditForm(f => ({ ...f, email: e.target.value.trim() }))}
                placeholder="email@domain.com"
              />
            </div>
            <div className="space-y-1.5">
              <Label>{isAr ? 'رقم الهاتف' : 'Phone'}</Label>
              <Input
                value={editForm.phone}
                onChange={e => setEditForm(f => ({ ...f, phone: e.target.value }))}
                placeholder="+966 5X XXX XXXX"
              />
            </div>
            {editErr && (
              <p className="text-sm text-destructive bg-destructive/10 px-3 py-2 rounded-md">{editErr}</p>
            )}
            <div className="flex justify-end gap-3 pt-1">
              <Button type="button" variant="outline" onClick={() => setEditOpen(false)}>{tr.cancel}</Button>
              <Button type="submit" disabled={editing} className="bg-primary hover:bg-primary/90 min-w-[80px]">
                {editing ? <span className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin inline-block"/> : tr.save}
              </Button>
            </div>
          </form>
        </DialogContent>
      </Dialog>

      {/* ══════════════ CONFIRM TOGGLE ACTIVE ══════════════ */}
      <Dialog open={confirmOpen} onOpenChange={setConfirmOpen}>
        <DialogContent dir={isAr ? 'rtl' : 'ltr'} className="max-w-sm">
          <DialogHeader>
            <DialogTitle>
              {confirmTarget?.active
                ? (isAr ? 'تعطيل الحساب' : 'Deactivate Account')
                : (isAr ? 'تفعيل الحساب'  : 'Activate Account')}
            </DialogTitle>
          </DialogHeader>
          <div className="py-2">
            <p className="text-sm text-muted-foreground">
              {confirmTarget?.active
                ? (isAr
                  ? `هل تريد تعطيل حساب "${confirmTarget?.fullName}"؟ لن يتمكن من تسجيل الدخول.`
                  : `Deactivate "${confirmTarget?.fullName}"? They won't be able to log in.`)
                : (isAr
                  ? `هل تريد إعادة تفعيل حساب "${confirmTarget?.fullName}"؟`
                  : `Reactivate "${confirmTarget?.fullName}"?`)}
            </p>
          </div>
          <div className="flex justify-end gap-3">
            <Button variant="outline" onClick={() => setConfirmOpen(false)} disabled={toggling}>
              {tr.cancel}
            </Button>
            <Button
              onClick={handleToggle}
              disabled={toggling}
              className={`min-w-[80px] ${confirmTarget?.active ? 'bg-destructive hover:bg-destructive/90' : 'bg-green-600 hover:bg-green-700'}`}
            >
              {toggling
                ? <span className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin inline-block"/>
                : (confirmTarget?.active ? (isAr ? 'تعطيل' : 'Deactivate') : (isAr ? 'تفعيل' : 'Activate'))}
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    </DashboardLayout>
  );
}

/* ─────────────────── helpers ─────────────────── */
function emptyCreate() {
  return {
    username: '', fullName: '', fullNameAr: '', email: '', password: '',
    role: 'technician' as UserRole,
    centerId: '', regionId: '', controlRoomId: '',
  };
}
function emptyEdit() {
  return { fullName: '', fullNameAr: '', email: '', phone: '' };
}
function extractMsg(err: unknown, isAr: boolean): string {
  const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
  return msg ?? (isAr ? 'حدث خطأ، حاول مرة أخرى' : 'An error occurred, please try again');
}
