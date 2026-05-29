'use client';
import { useEffect, useState, useCallback, useRef } from 'react';
import DashboardLayout from '@/components/layout/DashboardLayout';
import { useAuthStore } from '@/store/authStore';
import { useT, roleLabels } from '@/lib/i18n';
import { userApi, centerApi, regionApi, controlRoomApi, importApi } from '@/lib/api';
import { User, UserRole } from '@/lib/types';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Plus, Search, UserCheck, UserX, Pencil, Power, PowerOff, KeyRound, Upload, Download, FileSpreadsheet, ChevronDown } from 'lucide-react';

/* ─────────────────── constants ─────────────────── */
const ALL_ROLES: UserRole[] = [
  'system_admin','region_supervisor','hospital_supervisor','control_room',
  'technician','maintenance_manager','electrical_manager','warehouse',
  'operating_company_manager','resident','department','other',
];
const REGION_ROLES    = new Set<UserRole>(['region_supervisor']);
const CENTER_ROLES    = new Set<UserRole>([
  'hospital_supervisor','control_room','technician','maintenance_manager',
  'electrical_manager','warehouse','operating_company_manager','resident','department',
]);
const SINGLE_CR_ROLES = new Set<UserRole>(['control_room']);
const MULTI_CR_ROLES  = new Set<UserRole>(['resident','department','other']);

const SEL = "w-full h-10 rounded-md border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring";

type DropItem = { id: string; name: string; nameAr: string };

function emptyCreate() {
  return {
    username: '', fullName: '', fullNameAr: '', email: '', phone: '', password: '',
    role: 'technician' as UserRole,
    centerId: '', regionId: '',
    controlRoomId:  '',
    controlRoomIds: [] as string[],
    // Resident
    complexName: '', buildingNumber: '', unitNumber: '',
    // Department
    departmentName: '', departmentSupervisor: '', departmentEmail: '', extensionNumber: '',
    // Other
    jobTitle: '',
  };
}
function emptyEdit() {
  return { fullName: '', fullNameAr: '', email: '', phone: '' };
}
function extractMsg(err: unknown, isAr: boolean): string {
  const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
  return msg ?? (isAr ? 'حدث خطأ، حاول مرة أخرى' : 'An error occurred, please try again');
}
function downloadBlob(data: Blob, filename: string) {
  const url = URL.createObjectURL(data);
  const a = document.createElement('a');
  a.href = url; a.download = filename; a.click();
  URL.revokeObjectURL(url);
}

/* ─────────────────── component ──────────────────── */
export default function UsersPage() {
  const { lang, user: me } = useAuthStore();
  const tr   = useT(lang);
  const isAr = lang === 'ar';
  const isAdmin = me?.role === 'system_admin';

  /* list */
  const [users,   setUsers]   = useState<User[]>([]);
  const [loading, setLoading] = useState(true);
  const [search,  setSearch]  = useState('');

  /* dropdown data */
  const [centers,      setCenters]      = useState<DropItem[]>([]);
  const [regions,      setRegions]      = useState<DropItem[]>([]);
  const [controlRooms, setControlRooms] = useState<DropItem[]>([]);
  const [crLoading,    setCrLoading]    = useState(false);

  /* create */
  const [createOpen, setCreateOpen] = useState(false);
  const [createForm, setCreateForm] = useState(emptyCreate());
  const [createErr,  setCreateErr]  = useState('');
  const [creating,   setCreating]   = useState(false);

  /* edit */
  const [editOpen,   setEditOpen]   = useState(false);
  const [editForm,   setEditForm]   = useState(emptyEdit());
  const [editErr,    setEditErr]    = useState('');
  const [editing,    setEditing]    = useState(false);
  const [editTarget, setEditTarget] = useState<User | null>(null);

  /* toggle active */
  const [confirmOpen,   setConfirmOpen]   = useState(false);
  const [confirmTarget, setConfirmTarget] = useState<User | null>(null);
  const [toggling,      setToggling]      = useState(false);

  /* reset password */
  const [resetOpen,     setResetOpen]     = useState(false);
  const [resetTarget,   setResetTarget]   = useState<User | null>(null);
  const [resetPwd,      setResetPwd]      = useState('');
  const [resetErr,      setResetErr]      = useState('');
  const [resetting,     setResetting]     = useState(false);

  /* bulk import */
  const [importOpen,   setImportOpen]   = useState(false);
  const [importType,   setImportType]   = useState<'resident'|'department'>('resident');
  const [importFile,   setImportFile]   = useState<File|null>(null);
  const [importing,    setImporting]    = useState(false);
  const [importResult, setImportResult] = useState<{success:number;failed:number;errors:string[]}|null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  /* import dropdown button */
  const [importMenuOpen, setImportMenuOpen] = useState(false);

  /* ─── computed ─── */
  const needsRegion    = REGION_ROLES.has(createForm.role);
  const needsCenter    = CENTER_ROLES.has(createForm.role);
  const needsSingleCR  = SINGLE_CR_ROLES.has(createForm.role);
  const needsMultiCR   = MULTI_CR_ROLES.has(createForm.role);
  const isResident     = createForm.role === 'resident';
  const isDepartment   = createForm.role === 'department';
  const isOther        = createForm.role === 'other';

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

  /* ─── open create ─── */
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

  /* ─── role change ─── */
  const handleRoleChange = (role: UserRole) => {
    setCreateForm(f => ({ ...f, role, centerId:'', regionId:'', controlRoomId:'', controlRoomIds:[] }));
    setControlRooms([]);
  };

  /* ─── center change → load CRs ─── */
  const handleCenterChange = async (centerId: string) => {
    setCreateForm(f => ({ ...f, centerId, controlRoomId:'', controlRoomIds:[] }));
    setControlRooms([]);
    if (!centerId) return;
    if (!needsSingleCR && !needsMultiCR) return;
    setCrLoading(true);
    try {
      const res = await controlRoomApi.getAll(centerId);
      setControlRooms(res.data ?? []);
    } catch { /* silent */ }
    finally { setCrLoading(false); }
  };

  /* ─── toggle multi-CR checkbox ─── */
  const toggleCR = (id: string) => {
    setCreateForm(f => ({
      ...f,
      controlRoomIds: f.controlRoomIds.includes(id)
        ? f.controlRoomIds.filter(x => x !== id)
        : [...f.controlRoomIds, id],
    }));
  };

  /* ─── create submit ─── */
  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setCreateErr('');
    if (needsRegion && !createForm.regionId)           { setCreateErr(isAr ? 'اختر المنطقة'     : 'Select a region');       return; }
    if (needsCenter && !createForm.centerId)           { setCreateErr(isAr ? 'اختر المركز'      : 'Select a center');       return; }
    if (needsSingleCR && !createForm.controlRoomId)   { setCreateErr(isAr ? 'اختر غرفة التحكم' : 'Select a control room'); return; }

    const payload: Record<string, unknown> = {
      username: createForm.username,
      fullName: createForm.fullName,
      password: createForm.password,
      role:     createForm.role,
    };
    if (createForm.fullNameAr)      payload.fullNameAr    = createForm.fullNameAr;
    if (createForm.email)           payload.email         = createForm.email;
    if (createForm.phone)           payload.phone         = createForm.phone;
    if (createForm.centerId)        payload.centerId      = createForm.centerId;
    if (createForm.regionId)        payload.regionId      = createForm.regionId;
    if (createForm.controlRoomId)   payload.controlRoomId = createForm.controlRoomId;
    if (createForm.controlRoomIds.length > 0) payload.controlRoomIds = createForm.controlRoomIds;

    // Resident fields
    if (isResident) {
      if (createForm.complexName)    payload.complexName    = createForm.complexName;
      if (createForm.buildingNumber) payload.buildingNumber = createForm.buildingNumber;
      if (createForm.unitNumber)     payload.unitNumber     = createForm.unitNumber;
    }
    // Department fields
    if (isDepartment) {
      if (createForm.departmentName)       payload.departmentName       = createForm.departmentName;
      if (createForm.departmentSupervisor) payload.departmentSupervisor = createForm.departmentSupervisor;
      if (createForm.departmentEmail)      payload.departmentEmail      = createForm.departmentEmail;
      if (createForm.extensionNumber)      payload.extensionNumber      = createForm.extensionNumber;
    }
    // Other / any role with jobTitle
    if (createForm.jobTitle) payload.jobTitle = createForm.jobTitle;

    setCreating(true);
    try {
      await userApi.create(payload);
      setCreateOpen(false);
      loadUsers();
    } catch (err) {
      setCreateErr(extractMsg(err, isAr));
    } finally { setCreating(false); }
  };

  /* ─── edit ─── */
  const openEdit = (u: User) => {
    setEditTarget(u);
    setEditForm({ fullName: u.fullName??'', fullNameAr: u.fullNameAr??'', email: u.email??'', phone: u.phone??'' });
    setEditErr(''); setEditOpen(true);
  };
  const handleEdit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editTarget) return;
    setEditErr('');
    if (!editForm.fullName.trim()) { setEditErr(isAr ? 'الاسم مطلوب' : 'Name is required'); return; }
    setEditing(true);
    try {
      await userApi.update(String(editTarget.id), editForm);
      setEditOpen(false); loadUsers();
    } catch (err) { setEditErr(extractMsg(err, isAr)); }
    finally { setEditing(false); }
  };

  /* ─── toggle active ─── */
  const openConfirm = (u: User) => { setConfirmTarget(u); setConfirmOpen(true); };
  const handleToggle = async () => {
    if (!confirmTarget) return;
    setToggling(true);
    try {
      await userApi.toggleActive(String(confirmTarget.id), !confirmTarget.active);
      setConfirmOpen(false); loadUsers();
    } catch { /* silent */ }
    finally { setToggling(false); }
  };

  /* ─── reset password ─── */
  const openReset = (u: User) => { setResetTarget(u); setResetPwd(''); setResetErr(''); setResetOpen(true); };
  const handleReset = async (e: React.FormEvent) => {
    e.preventDefault();
    if (resetPwd.length < 8) { setResetErr(isAr ? 'كلمة المرور يجب أن تكون 8 أحرف على الأقل' : 'Min 8 characters'); return; }
    setResetting(true);
    try {
      await userApi.resetPassword(String(resetTarget!.id), resetPwd);
      setResetOpen(false);
    } catch (err) { setResetErr(extractMsg(err, isAr)); }
    finally { setResetting(false); }
  };

  /* ─── bulk import ─── */
  const openImport = (type: 'resident'|'department') => {
    setImportType(type); setImportFile(null); setImportResult(null);
    setImportMenuOpen(false); setImportOpen(true);
  };
  const downloadTemplate = async () => {
    try {
      const res = importType === 'resident'
        ? await importApi.downloadResidentTemplate()
        : await importApi.downloadDepartmentTemplate();
      downloadBlob(res.data, importType === 'resident' ? 'residents_template.xlsx' : 'departments_template.xlsx');
    } catch { /* silent */ }
  };
  const handleImport = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!importFile) return;
    setImporting(true); setImportResult(null);
    try {
      const res = importType === 'resident'
        ? await importApi.importResidents(importFile)
        : await importApi.importDepartments(importFile);
      setImportResult(res.data);
      if (res.data.success > 0) loadUsers();
    } catch (err) {
      setImportResult({ success: 0, failed: 1, errors: [extractMsg(err, isAr)] });
    } finally { setImporting(false); }
  };

  /* ─── filtered ─── */
  const filtered = users.filter(u =>
    !search ||
    u.fullName?.toLowerCase().includes(search.toLowerCase()) ||
    u.username?.toLowerCase().includes(search.toLowerCase()) ||
    u.email?.toLowerCase().includes(search.toLowerCase())
  );

  /* ─── CR selection section (shared for multi-CR roles) ─── */
  const CRMultiSection = () => (
    <div className="space-y-1.5">
      <Label className="text-amber-700 font-semibold">
        {isAr ? 'ربط بغرف التحكم' : 'Link to Control Rooms'}
        {(isResident || isDepartment) && <span className="text-destructive"> *</span>}
      </Label>
      {!createForm.centerId ? (
        <p className="text-sm bg-blue-50 dark:bg-blue-950/30 text-blue-700 dark:text-blue-300 px-3 py-2 rounded-md">
          {isAr ? 'اختر المركز أولاً' : 'Select a center first'}
        </p>
      ) : crLoading ? (
        <p className="text-sm text-muted-foreground px-3 py-2">{isAr ? 'جاري التحميل...' : 'Loading...'}</p>
      ) : controlRooms.length === 0 ? (
        <p className="text-sm bg-destructive/10 text-destructive px-3 py-2 rounded-md">
          {isAr ? 'لا توجد غرف تحكم لهذا المركز' : 'No control rooms for this center'}
        </p>
      ) : (
        <div className="border rounded-md p-3 space-y-2 max-h-44 overflow-y-auto bg-muted/20">
          {controlRooms.map(cr => (
            <label key={cr.id} className="flex items-center gap-2 cursor-pointer hover:bg-muted/40 px-2 py-1 rounded">
              <input
                type="checkbox"
                checked={createForm.controlRoomIds.includes(cr.id)}
                onChange={() => toggleCR(cr.id)}
                className="w-4 h-4 accent-primary"
              />
              <span className="text-sm">{isAr ? cr.nameAr || cr.name : cr.name}</span>
            </label>
          ))}
          {createForm.controlRoomIds.length > 0 && (
            <p className="text-xs text-primary pt-1">
              {isAr ? `تم اختيار ${createForm.controlRoomIds.length} غرفة` : `${createForm.controlRoomIds.length} selected`}
            </p>
          )}
        </div>
      )}
    </div>
  );

  /* ════════════════════════════════ RENDER ════════════════════════════════ */
  return (
    <DashboardLayout>
      <div className="max-w-6xl mx-auto space-y-5">

        {/* Header */}
        <div className="flex items-center justify-between gap-3 flex-wrap">
          <div>
            <h1 className="text-2xl font-bold">{tr.users}</h1>
            <p className="text-sm text-muted-foreground">{filtered.length} {isAr ? 'مستخدم' : 'users'}</p>
          </div>
          <div className="flex items-center gap-2">
            {/* Bulk import dropdown */}
            <div className="relative">
              <Button
                variant="outline"
                className="gap-2"
                onClick={() => setImportMenuOpen(v => !v)}
              >
                <FileSpreadsheet size={16}/>
                {isAr ? 'استيراد Excel' : 'Import Excel'}
                <ChevronDown size={14}/>
              </Button>
              {importMenuOpen && (
                <div className="absolute end-0 mt-1 w-52 bg-popover border rounded-lg shadow-lg z-50 py-1">
                  <button
                    onClick={() => openImport('resident')}
                    className="w-full text-start px-4 py-2 text-sm hover:bg-muted/60 flex items-center gap-2"
                  >
                    <Upload size={14}/>
                    {isAr ? 'استيراد ساكنين' : 'Import Residents'}
                  </button>
                  <button
                    onClick={() => openImport('department')}
                    className="w-full text-start px-4 py-2 text-sm hover:bg-muted/60 flex items-center gap-2"
                  >
                    <Upload size={14}/>
                    {isAr ? 'استيراد أقسام' : 'Import Departments'}
                  </button>
                </div>
              )}
            </div>

            <Button onClick={openCreate} className="gap-2 bg-primary hover:bg-primary/90">
              <Plus size={16}/> {isAr ? 'مستخدم جديد' : 'New User'}
            </Button>
          </div>
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
                      {[tr.name, tr.username, tr.email, isAr?'الجوال':'Phone', tr.role, isAr?'المركز':'Center', tr.status, isAr?'الإجراءات':'Actions'].map(h => (
                        <th key={h} className="text-start px-4 py-3 font-medium text-muted-foreground whitespace-nowrap">{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {filtered.length === 0 ? (
                      <tr><td colSpan={8} className="text-center py-12 text-muted-foreground">{tr.noData}</td></tr>
                    ) : filtered.map(u => (
                      <tr key={u.id} className="border-b last:border-0 hover:bg-muted/30 transition-colors">
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
                        <td className="px-4 py-3 text-muted-foreground font-mono text-xs">{u.username}</td>
                        <td className="px-4 py-3 text-muted-foreground text-xs max-w-[140px] truncate">{u.email || '—'}</td>
                        <td className="px-4 py-3 text-muted-foreground text-xs">{(u as User & {phone?:string}).phone || '—'}</td>
                        <td className="px-4 py-3">
                          <Badge variant="outline" className="text-xs whitespace-nowrap">
                            {roleLabels[u.role]?.[lang] ?? u.role}
                          </Badge>
                        </td>
                        <td className="px-4 py-3 text-muted-foreground text-xs">
                          {u.center ? (isAr ? u.center.nameAr ?? u.center.name : u.center.name) : '—'}
                        </td>
                        <td className="px-4 py-3">
                          {u.active
                            ? <span className="flex items-center gap-1 text-green-600 text-xs font-medium"><UserCheck size={13}/>{tr.active}</span>
                            : <span className="flex items-center gap-1 text-destructive text-xs font-medium"><UserX size={13}/>{tr.inactive}</span>}
                        </td>
                        <td className="px-4 py-3">
                          <div className="flex items-center gap-1">
                            <button onClick={() => openEdit(u)} title={isAr?'تعديل':'Edit'}
                              className="p-1.5 rounded hover:bg-blue-50 hover:text-blue-600 text-muted-foreground transition-colors">
                              <Pencil size={14}/>
                            </button>
                            {isAdmin && String(me?.id) !== String(u.id) && (
                              <button onClick={() => openReset(u)} title={isAr?'إعادة تعيين كلمة المرور':'Reset Password'}
                                className="p-1.5 rounded hover:bg-yellow-50 hover:text-yellow-600 text-muted-foreground transition-colors">
                                <KeyRound size={14}/>
                              </button>
                            )}
                            {String(me?.id) !== String(u.id) && (
                              <button onClick={() => openConfirm(u)}
                                title={u.active?(isAr?'تعطيل':'Deactivate'):(isAr?'تفعيل':'Activate')}
                                className={`p-1.5 rounded transition-colors ${u.active?'hover:bg-red-50 hover:text-red-600':'hover:bg-green-50 hover:text-green-600'} text-muted-foreground`}>
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
        <DialogContent dir={isAr?'rtl':'ltr'} className="max-w-xl max-h-[92vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle>{isAr?'إنشاء مستخدم جديد':'Create New User'}</DialogTitle>
          </DialogHeader>
          <form onSubmit={handleCreate} className="space-y-4">

            {/* Basic info — 2 cols */}
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <Label>{tr.fullName} *</Label>
                <Input required value={createForm.fullName}
                  onChange={e => setCreateForm(f => ({...f, fullName: e.target.value}))}
                  placeholder={isAr?'الاسم الكامل':'Full name'}/>
              </div>
              <div className="space-y-1.5">
                <Label>{isAr?'الاسم بالعربية':'Arabic Name'}</Label>
                <Input dir="rtl" value={createForm.fullNameAr}
                  onChange={e => setCreateForm(f => ({...f, fullNameAr: e.target.value}))}
                  placeholder="الاسم الكامل"/>
              </div>
              <div className="space-y-1.5">
                <Label>{tr.username} *</Label>
                <Input required value={createForm.username}
                  onChange={e => setCreateForm(f => ({...f, username: e.target.value.trim()}))}
                  placeholder="user.name"/>
              </div>
              <div className="space-y-1.5">
                <Label>{isAr?'رقم الجوال':'Phone'}</Label>
                <Input value={createForm.phone}
                  onChange={e => setCreateForm(f => ({...f, phone: e.target.value}))}
                  placeholder="+966 5X XXX XXXX" type="tel"/>
              </div>
              <div className="space-y-1.5">
                <Label>{tr.email}</Label>
                <Input type="email" value={createForm.email}
                  onChange={e => setCreateForm(f => ({...f, email: e.target.value.trim()}))}
                  placeholder="email@domain.com"/>
              </div>
              <div className="space-y-1.5">
                <Label>{tr.password} *</Label>
                <Input required type="password" value={createForm.password}
                  onChange={e => setCreateForm(f => ({...f, password: e.target.value}))}
                  placeholder={isAr?'8 أحرف على الأقل':'8+ characters'} minLength={8}/>
              </div>
            </div>

            {/* Role */}
            <div className="space-y-1.5">
              <Label>{tr.role} *</Label>
              <select className={SEL} value={createForm.role}
                onChange={e => handleRoleChange(e.target.value as UserRole)}>
                {ALL_ROLES.map(r => (
                  <option key={r} value={r}>{roleLabels[r]?.[lang] ?? r}</option>
                ))}
              </select>
            </div>

            {/* Other role → job title */}
            {isOther && (
              <div className="space-y-1.5">
                <Label>{isAr?'المسمى الوظيفي':'Job Title'} *</Label>
                <Input required value={createForm.jobTitle}
                  onChange={e => setCreateForm(f => ({...f, jobTitle: e.target.value}))}
                  placeholder={isAr?'أدخل المسمى الوظيفي':'Enter job title'}/>
              </div>
            )}

            {/* Region (region_supervisor) */}
            {needsRegion && (
              <div className="space-y-1.5">
                <Label className="text-amber-700 font-semibold">
                  {isAr?'⚠ ربط بالمنطقة (مطلوب)':'⚠ Link to Region (required)'}
                </Label>
                {regions.length === 0 ? (
                  <p className="text-sm bg-destructive/10 text-destructive px-3 py-2 rounded-md">
                    {isAr?'لا توجد مناطق — أضف منطقة أولاً':'No regions — add one first'}
                  </p>
                ) : (
                  <select className={SEL} value={createForm.regionId}
                    onChange={e => setCreateForm(f => ({...f, regionId: e.target.value}))}>
                    <option value="">{isAr?'— اختر المنطقة —':'— Select Region —'}</option>
                    {regions.map(r => <option key={r.id} value={r.id}>{isAr?r.nameAr||r.name:r.name}</option>)}
                  </select>
                )}
              </div>
            )}

            {/* Center */}
            {needsCenter && (
              <div className="space-y-1.5">
                <Label className="text-amber-700 font-semibold">
                  {isAr?'⚠ ربط بالمركز (مطلوب)':'⚠ Link to Center (required)'}
                </Label>
                {centers.length === 0 ? (
                  <p className="text-sm bg-destructive/10 text-destructive px-3 py-2 rounded-md">
                    {isAr?'لا توجد مراكز — أضف مركزاً أولاً':'No centers — add a center first'}
                  </p>
                ) : (
                  <select className={SEL} value={createForm.centerId}
                    onChange={e => handleCenterChange(e.target.value)}>
                    <option value="">{isAr?'— اختر المركز —':'— Select Center —'}</option>
                    {centers.map(c => <option key={c.id} value={c.id}>{isAr?c.nameAr||c.name:c.name}</option>)}
                  </select>
                )}
              </div>
            )}

            {/* Single CR for control_room role */}
            {needsSingleCR && (
              <div className="space-y-1.5">
                <Label className="text-amber-700 font-semibold">
                  {isAr?'⚠ ربط بغرفة تحكم (مطلوب)':'⚠ Link to Control Room (required)'}
                </Label>
                {!createForm.centerId ? (
                  <p className="text-sm bg-blue-50 dark:bg-blue-950/30 text-blue-700 px-3 py-2 rounded-md">
                    {isAr?'اختر المركز أولاً':'Select a center first'}
                  </p>
                ) : crLoading ? (
                  <p className="text-sm text-muted-foreground px-3 py-2">{isAr?'جاري التحميل...':'Loading...'}</p>
                ) : controlRooms.length === 0 ? (
                  <p className="text-sm bg-destructive/10 text-destructive px-3 py-2 rounded-md">
                    {isAr?'لا توجد غرف تحكم':'No control rooms found'}
                  </p>
                ) : (
                  <select className={SEL} value={createForm.controlRoomId}
                    onChange={e => setCreateForm(f => ({...f, controlRoomId: e.target.value}))}>
                    <option value="">{isAr?'— اختر غرفة التحكم —':'— Select Control Room —'}</option>
                    {controlRooms.map(cr => <option key={cr.id} value={cr.id}>{isAr?cr.nameAr||cr.name:cr.name}</option>)}
                  </select>
                )}
              </div>
            )}

            {/* Multi CR for resident / department / other */}
            {(needsMultiCR && needsCenter) && <CRMultiSection/>}
            {(isOther && !needsCenter) && (
              <div className="space-y-1.5">
                <Label>{isAr?'ربط بمركز (اختياري)':'Link to Center (optional)'}</Label>
                {centers.length > 0 && (
                  <select className={SEL} value={createForm.centerId}
                    onChange={e => handleCenterChange(e.target.value)}>
                    <option value="">{isAr?'— اختر المركز —':'— Select Center (optional) —'}</option>
                    {centers.map(c => <option key={c.id} value={c.id}>{isAr?c.nameAr||c.name:c.name}</option>)}
                  </select>
                )}
                {createForm.centerId && <CRMultiSection/>}
              </div>
            )}

            {/* ══ Resident-specific fields ══ */}
            {isResident && (
              <div className="border rounded-lg p-3 space-y-3 bg-muted/20">
                <p className="text-sm font-semibold text-primary">{isAr?'معلومات السكن':'Residence Information'}</p>
                <div className="grid grid-cols-3 gap-3">
                  <div className="space-y-1.5">
                    <Label>{isAr?'المجمع':'Complex'}</Label>
                    <Input value={createForm.complexName}
                      onChange={e => setCreateForm(f => ({...f, complexName: e.target.value}))}
                      placeholder={isAr?'اسم المجمع':'Complex name'}/>
                  </div>
                  <div className="space-y-1.5">
                    <Label>{isAr?'المبنى':'Building'}</Label>
                    <Input value={createForm.buildingNumber}
                      onChange={e => setCreateForm(f => ({...f, buildingNumber: e.target.value}))}
                      placeholder={isAr?'رقم المبنى':'B1'}/>
                  </div>
                  <div className="space-y-1.5">
                    <Label>{isAr?'الوحدة':'Unit'}</Label>
                    <Input value={createForm.unitNumber}
                      onChange={e => setCreateForm(f => ({...f, unitNumber: e.target.value}))}
                      placeholder={isAr?'رقم الوحدة':'101'}/>
                  </div>
                </div>
              </div>
            )}

            {/* ══ Department-specific fields ══ */}
            {isDepartment && (
              <div className="border rounded-lg p-3 space-y-3 bg-muted/20">
                <p className="text-sm font-semibold text-primary">{isAr?'معلومات القسم':'Department Information'}</p>
                <div className="grid grid-cols-2 gap-3">
                  <div className="space-y-1.5">
                    <Label>{isAr?'اسم القسم':'Department Name'} *</Label>
                    <Input required value={createForm.departmentName}
                      onChange={e => setCreateForm(f => ({...f, departmentName: e.target.value}))}
                      placeholder={isAr?'اسم القسم':'Dept. name'}/>
                  </div>
                  <div className="space-y-1.5">
                    <Label>{isAr?'مشرف القسم':'Supervisor'}</Label>
                    <Input value={createForm.departmentSupervisor}
                      onChange={e => setCreateForm(f => ({...f, departmentSupervisor: e.target.value}))}
                      placeholder={isAr?'اسم المشرف':'Supervisor name'}/>
                  </div>
                  <div className="space-y-1.5">
                    <Label>{isAr?'إيميل القسم':'Dept. Email'}</Label>
                    <Input type="email" value={createForm.departmentEmail}
                      onChange={e => setCreateForm(f => ({...f, departmentEmail: e.target.value}))}
                      placeholder="dept@hospital.sa"/>
                  </div>
                  <div className="space-y-1.5">
                    <Label>{isAr?'رقم التحويلة':'Extension'}</Label>
                    <Input value={createForm.extensionNumber}
                      onChange={e => setCreateForm(f => ({...f, extensionNumber: e.target.value}))}
                      placeholder="1234"/>
                  </div>
                </div>
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
        <DialogContent dir={isAr?'rtl':'ltr'} className="max-w-md">
          <DialogHeader>
            <DialogTitle>
              {isAr?'تعديل الحساب':'Edit Account'}
              {editTarget && <span className="text-muted-foreground font-normal text-sm ms-2">— {editTarget.username}</span>}
            </DialogTitle>
          </DialogHeader>
          <form onSubmit={handleEdit} className="space-y-4">
            <div className="space-y-1.5">
              <Label>{tr.fullName} *</Label>
              <Input required value={editForm.fullName} onChange={e => setEditForm(f => ({...f, fullName: e.target.value}))}/>
            </div>
            <div className="space-y-1.5">
              <Label>{isAr?'الاسم بالعربية':'Arabic Name'}</Label>
              <Input dir="rtl" value={editForm.fullNameAr} onChange={e => setEditForm(f => ({...f, fullNameAr: e.target.value}))}/>
            </div>
            <div className="space-y-1.5">
              <Label>{tr.email}</Label>
              <Input type="email" value={editForm.email}
                onChange={e => setEditForm(f => ({...f, email: e.target.value.trim()}))}
                placeholder="email@domain.com"/>
            </div>
            <div className="space-y-1.5">
              <Label>{isAr?'رقم الجوال':'Phone'}</Label>
              <Input value={editForm.phone} onChange={e => setEditForm(f => ({...f, phone: e.target.value}))}
                placeholder="+966 5X XXX XXXX" type="tel"/>
            </div>
            {editErr && <p className="text-sm text-destructive bg-destructive/10 px-3 py-2 rounded-md">{editErr}</p>}
            <div className="flex justify-end gap-3 pt-1">
              <Button type="button" variant="outline" onClick={() => setEditOpen(false)}>{tr.cancel}</Button>
              <Button type="submit" disabled={editing} className="bg-primary hover:bg-primary/90 min-w-[80px]">
                {editing ? <span className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin inline-block"/> : tr.save}
              </Button>
            </div>
          </form>
        </DialogContent>
      </Dialog>

      {/* ══════════════ CONFIRM TOGGLE ══════════════ */}
      <Dialog open={confirmOpen} onOpenChange={setConfirmOpen}>
        <DialogContent dir={isAr?'rtl':'ltr'} className="max-w-sm">
          <DialogHeader>
            <DialogTitle>
              {confirmTarget?.active ? (isAr?'تعطيل الحساب':'Deactivate Account') : (isAr?'تفعيل الحساب':'Activate Account')}
            </DialogTitle>
          </DialogHeader>
          <p className="text-sm text-muted-foreground py-2">
            {confirmTarget?.active
              ? (isAr ? `هل تريد تعطيل حساب "${confirmTarget?.fullName}"؟` : `Deactivate "${confirmTarget?.fullName}"?`)
              : (isAr ? `هل تريد إعادة تفعيل حساب "${confirmTarget?.fullName}"؟` : `Reactivate "${confirmTarget?.fullName}"?`)}
          </p>
          <div className="flex justify-end gap-3">
            <Button variant="outline" onClick={() => setConfirmOpen(false)} disabled={toggling}>{tr.cancel}</Button>
            <Button onClick={handleToggle} disabled={toggling}
              className={`min-w-[80px] ${confirmTarget?.active?'bg-destructive hover:bg-destructive/90':'bg-green-600 hover:bg-green-700'}`}>
              {toggling ? <span className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin inline-block"/> :
                (confirmTarget?.active?(isAr?'تعطيل':'Deactivate'):(isAr?'تفعيل':'Activate'))}
            </Button>
          </div>
        </DialogContent>
      </Dialog>

      {/* ══════════════ RESET PASSWORD ══════════════ */}
      <Dialog open={resetOpen} onOpenChange={setResetOpen}>
        <DialogContent dir={isAr?'rtl':'ltr'} className="max-w-sm">
          <DialogHeader>
            <DialogTitle>{isAr?'إعادة تعيين كلمة المرور':'Reset Password'}</DialogTitle>
          </DialogHeader>
          <form onSubmit={handleReset} className="space-y-4">
            <p className="text-sm text-muted-foreground">
              {isAr ? `إعادة تعيين كلمة مرور: "${resetTarget?.fullName}"` : `Reset password for: "${resetTarget?.fullName}"`}
            </p>
            <div className="space-y-1.5">
              <Label>{isAr?'كلمة المرور الجديدة':'New Password'} *</Label>
              <Input type="password" value={resetPwd} onChange={e => setResetPwd(e.target.value)}
                placeholder={isAr?'8 أحرف على الأقل':'8+ characters'} minLength={8} required/>
            </div>
            {resetErr && <p className="text-sm text-destructive">{resetErr}</p>}
            <div className="flex justify-end gap-3">
              <Button type="button" variant="outline" onClick={() => setResetOpen(false)} disabled={resetting}>{tr.cancel}</Button>
              <Button type="submit" disabled={resetting} className="bg-yellow-600 hover:bg-yellow-700 min-w-[80px]">
                {resetting ? <span className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin inline-block"/> : (isAr?'إعادة تعيين':'Reset')}
              </Button>
            </div>
          </form>
        </DialogContent>
      </Dialog>

      {/* ══════════════ BULK IMPORT DIALOG ══════════════ */}
      <Dialog open={importOpen} onOpenChange={setImportOpen}>
        <DialogContent dir={isAr?'rtl':'ltr'} className="max-w-md">
          <DialogHeader>
            <DialogTitle>
              {importType === 'resident'
                ? (isAr?'استيراد ساكنين':'Import Residents')
                : (isAr?'استيراد أقسام':'Import Departments')}
            </DialogTitle>
          </DialogHeader>
          <form onSubmit={handleImport} className="space-y-4">
            {/* Download template */}
            <div className="border rounded-lg p-3 bg-muted/20 flex items-center justify-between">
              <div>
                <p className="text-sm font-medium">{isAr?'تحميل قالب Excel':'Download Excel Template'}</p>
                <p className="text-xs text-muted-foreground">
                  {isAr?'قم بتحميل القالب، ثم أدخل البيانات وارفع الملف':'Download template, fill data, then upload'}
                </p>
              </div>
              <Button type="button" variant="outline" size="sm" onClick={downloadTemplate} className="gap-2 shrink-0">
                <Download size={14}/>
                {isAr?'تحميل':'Download'}
              </Button>
            </div>

            {/* Upload */}
            <div className="space-y-2">
              <Label>{isAr?'رفع ملف Excel':'Upload Excel File'} *</Label>
              <div
                onClick={() => fileRef.current?.click()}
                className="border-2 border-dashed rounded-lg p-6 text-center cursor-pointer hover:border-primary/50 hover:bg-muted/30 transition-colors"
              >
                <Upload size={24} className="mx-auto text-muted-foreground mb-2"/>
                {importFile ? (
                  <p className="text-sm font-medium text-primary">{importFile.name}</p>
                ) : (
                  <>
                    <p className="text-sm font-medium">{isAr?'اضغط لاختيار ملف':'Click to choose file'}</p>
                    <p className="text-xs text-muted-foreground mt-1">.xlsx</p>
                  </>
                )}
              </div>
              <input
                ref={fileRef}
                type="file"
                accept=".xlsx"
                className="hidden"
                onChange={e => setImportFile(e.target.files?.[0] ?? null)}
              />
            </div>

            {/* Results */}
            {importResult && (
              <div className={`rounded-lg p-3 text-sm ${importResult.failed === 0 ? 'bg-green-50 dark:bg-green-950/30 text-green-700 dark:text-green-300' : 'bg-amber-50 dark:bg-amber-950/30 text-amber-700 dark:text-amber-300'}`}>
                <p className="font-semibold">
                  {isAr
                    ? `تم إضافة ${importResult.success} — فشل ${importResult.failed}`
                    : `Added ${importResult.success} — Failed ${importResult.failed}`}
                </p>
                {importResult.errors.length > 0 && (
                  <ul className="mt-2 space-y-1 max-h-28 overflow-y-auto text-xs">
                    {importResult.errors.map((e, i) => <li key={i}>• {e}</li>)}
                  </ul>
                )}
              </div>
            )}

            <div className="flex justify-end gap-3">
              <Button type="button" variant="outline" onClick={() => setImportOpen(false)}>{tr.cancel}</Button>
              <Button type="submit" disabled={!importFile || importing} className="bg-primary hover:bg-primary/90 min-w-[80px] gap-2">
                {importing
                  ? <span className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin inline-block"/>
                  : <><Upload size={14}/>{isAr?'رفع واستيراد':'Upload & Import'}</>}
              </Button>
            </div>
          </form>
        </DialogContent>
      </Dialog>

      {/* close import menu on outside click */}
      {importMenuOpen && (
        <div className="fixed inset-0 z-40" onClick={() => setImportMenuOpen(false)}/>
      )}
    </DashboardLayout>
  );
}
