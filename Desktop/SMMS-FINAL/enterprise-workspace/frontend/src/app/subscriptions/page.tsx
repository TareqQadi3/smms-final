'use client';

import { useEffect, useState, useCallback } from 'react';
import DashboardLayout from '@/components/layout/DashboardLayout';
import { useAuthStore } from '@/store/authStore';
import { useT } from '@/lib/i18n';
import { subscriptionApi, centerApi } from '@/lib/api';
import { Subscription, Center } from '@/lib/types';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  CreditCard, Plus, RefreshCw, Trash2, CheckCircle, AlertCircle,
  XCircle, Clock, Users, ClipboardList,
} from 'lucide-react';

// ── Plan configuration ────────────────────────────────────
const PLAN_COLORS: Record<string, string> = {
  TRIAL:        'bg-yellow-100 text-yellow-800 border-yellow-300',
  BASIC:        'bg-blue-100 text-blue-800 border-blue-300',
  PROFESSIONAL: 'bg-purple-100 text-purple-800 border-purple-300',
  ENTERPRISE:   'bg-green-100 text-green-800 border-green-300',
};

const PLAN_DEFAULTS: Record<string, { maxUsers: number; maxWO: number; days: number }> = {
  TRIAL:        { maxUsers: 5,   maxWO: 50,  days: 30 },
  BASIC:        { maxUsers: 25,  maxWO: 200, days: 365 },
  PROFESSIONAL: { maxUsers: 75,  maxWO: -1,  days: 365 },
  ENTERPRISE:   { maxUsers: -1,  maxWO: -1,  days: -1 },
};

// ── Status badge helper ───────────────────────────────────
function StatusBadge({ sub }: { sub: Subscription }) {
  if (!sub.active) {
    return (
      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium bg-gray-100 text-gray-700">
        <XCircle size={12} /> ملغي
      </span>
    );
  }
  if (sub.expired) {
    return (
      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium bg-red-100 text-red-700">
        <AlertCircle size={12} /> منتهي
      </span>
    );
  }
  if (sub.daysRemaining !== -1 && sub.daysRemaining <= 15) {
    return (
      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium bg-orange-100 text-orange-700">
        <Clock size={12} /> ينتهي قريباً ({sub.daysRemaining} يوم)
      </span>
    );
  }
  return (
    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium bg-green-100 text-green-700">
      <CheckCircle size={12} /> ساري
    </span>
  );
}

// ── Add subscription dialog ───────────────────────────────
function AddDialog({
  centers,
  existingCenterIds,
  onClose,
  onSaved,
}: {
  centers: Center[];
  existingCenterIds: Set<string>;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [centerId, setCenterId] = useState('');
  const [plan, setPlan] = useState('TRIAL');
  const [startDate, setStartDate] = useState(new Date().toISOString().split('T')[0]);
  const [endDate, setEndDate] = useState('');
  const [maxUsers, setMaxUsers] = useState('');
  const [maxWO, setMaxWO] = useState('');
  const [notes, setNotes] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const availableCenters = centers.filter(c => !existingCenterIds.has(c.id));

  function applyPlanDefaults(selectedPlan: string) {
    const defaults = PLAN_DEFAULTS[selectedPlan];
    if (!defaults) return;
    setMaxUsers(defaults.maxUsers === -1 ? '' : String(defaults.maxUsers));
    setMaxWO(defaults.maxWO === -1 ? '' : String(defaults.maxWO));
    if (defaults.days > 0) {
      const d = new Date();
      d.setDate(d.getDate() + defaults.days);
      setEndDate(d.toISOString().split('T')[0]);
    } else {
      setEndDate('');
    }
  }

  async function handleSave() {
    if (!centerId) { setError('اختر مركزاً'); return; }
    setSaving(true);
    setError('');
    try {
      await subscriptionApi.create({
        centerId,
        plan,
        startDate,
        endDate: endDate || null,
        maxUsers: maxUsers ? Number(maxUsers) : null,
        maxWorkOrdersPerMonth: maxWO ? Number(maxWO) : null,
        notes: notes || null,
      });
      onSaved();
    } catch (e: unknown) {
      const err = e as { response?: { data?: { message?: string } } };
      setError(err?.response?.data?.message || 'فشل الحفظ');
    } finally {
      setSaving(false);
    }
  }

  return (
    <Dialog open onOpenChange={onClose}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>إضافة اشتراك جديد</DialogTitle>
        </DialogHeader>
        <div className="space-y-4">
          {error && (
            <p className="text-sm text-red-600 bg-red-50 rounded px-3 py-2">{error}</p>
          )}

          <div>
            <Label>المركز</Label>
            <select
              className="w-full mt-1 rounded-md border border-input bg-background px-3 py-2 text-sm"
              value={centerId}
              onChange={e => setCenterId(e.target.value)}
            >
              <option value="">-- اختر مركزاً --</option>
              {availableCenters.map(c => (
                <option key={c.id} value={c.id}>{c.nameAr || c.name} ({c.code})</option>
              ))}
            </select>
          </div>

          <div>
            <Label>الخطة</Label>
            <select
              className="w-full mt-1 rounded-md border border-input bg-background px-3 py-2 text-sm"
              value={plan}
              onChange={e => { setPlan(e.target.value); applyPlanDefaults(e.target.value); }}
            >
              <option value="TRIAL">تجريبي (30 يوم)</option>
              <option value="BASIC">أساسي (1 سنة)</option>
              <option value="PROFESSIONAL">احترافي (1 سنة)</option>
              <option value="ENTERPRISE">مؤسسي (بلا انتهاء)</option>
            </select>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <Label>تاريخ البدء</Label>
              <Input type="date" value={startDate} onChange={e => setStartDate(e.target.value)} className="mt-1" />
            </div>
            <div>
              <Label>تاريخ الانتهاء</Label>
              <Input type="date" value={endDate} onChange={e => setEndDate(e.target.value)} className="mt-1"
                placeholder="اتركه فارغاً = بلا انتهاء" />
            </div>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <Label>حد المستخدمين (-1=غير محدود)</Label>
              <Input type="number" value={maxUsers} onChange={e => setMaxUsers(e.target.value)}
                placeholder="-1" className="mt-1" />
            </div>
            <div>
              <Label>حد أوامر العمل/شهر</Label>
              <Input type="number" value={maxWO} onChange={e => setMaxWO(e.target.value)}
                placeholder="-1" className="mt-1" />
            </div>
          </div>

          <div>
            <Label>ملاحظات</Label>
            <Input value={notes} onChange={e => setNotes(e.target.value)} className="mt-1"
              placeholder="اختياري" />
          </div>
        </div>
        <DialogFooter className="gap-2">
          <Button variant="outline" onClick={onClose} disabled={saving}>إلغاء</Button>
          <Button onClick={handleSave} disabled={saving}>
            {saving ? 'جاري الحفظ...' : 'حفظ'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// ── Renew dialog ──────────────────────────────────────────
function RenewDialog({
  sub,
  onClose,
  onSaved,
}: { sub: Subscription; onClose: () => void; onSaved: () => void }) {
  const defaultEnd = new Date();
  defaultEnd.setFullYear(defaultEnd.getFullYear() + 1);
  const [newEndDate, setNewEndDate] = useState(defaultEnd.toISOString().split('T')[0]);
  const [notes, setNotes] = useState('');
  const [saving, setSaving] = useState(false);

  async function handleRenew() {
    setSaving(true);
    try {
      await subscriptionApi.renew(sub.id, newEndDate, notes || undefined);
      onSaved();
    } finally {
      setSaving(false);
    }
  }

  return (
    <Dialog open onOpenChange={onClose}>
      <DialogContent className="max-w-sm">
        <DialogHeader>
          <DialogTitle>تجديد اشتراك — {sub.centerNameAr || sub.centerName}</DialogTitle>
        </DialogHeader>
        <div className="space-y-4">
          <div>
            <Label>تاريخ الانتهاء الجديد</Label>
            <Input type="date" value={newEndDate} onChange={e => setNewEndDate(e.target.value)} className="mt-1" />
          </div>
          <div>
            <Label>ملاحظات</Label>
            <Input value={notes} onChange={e => setNotes(e.target.value)} className="mt-1" placeholder="اختياري" />
          </div>
        </div>
        <DialogFooter className="gap-2">
          <Button variant="outline" onClick={onClose} disabled={saving}>إلغاء</Button>
          <Button onClick={handleRenew} disabled={saving}>
            {saving ? 'جاري التجديد...' : 'تجديد'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// ── Edit dialog ───────────────────────────────────────────
function EditDialog({
  sub,
  onClose,
  onSaved,
}: { sub: Subscription; onClose: () => void; onSaved: () => void }) {
  const [plan, setPlan] = useState(sub.plan);
  const [startDate, setStartDate] = useState(sub.startDate);
  const [endDate, setEndDate] = useState(sub.endDate || '');
  const [maxUsers, setMaxUsers] = useState(String(sub.maxUsers));
  const [maxWO, setMaxWO] = useState(String(sub.maxWorkOrdersPerMonth));
  const [active, setActive] = useState(sub.active);
  const [notes, setNotes] = useState(sub.notes || '');
  const [saving, setSaving] = useState(false);

  async function handleSave() {
    setSaving(true);
    try {
      await subscriptionApi.update(sub.id, {
        plan,
        startDate,
        endDate: endDate || null,
        maxUsers: Number(maxUsers),
        maxWorkOrdersPerMonth: Number(maxWO),
        active,
        notes: notes || null,
      });
      onSaved();
    } finally {
      setSaving(false);
    }
  }

  return (
    <Dialog open onOpenChange={onClose}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>تعديل اشتراك — {sub.centerNameAr || sub.centerName}</DialogTitle>
        </DialogHeader>
        <div className="space-y-4">
          <div>
            <Label>الخطة</Label>
            <select
              className="w-full mt-1 rounded-md border border-input bg-background px-3 py-2 text-sm"
              value={plan}
              onChange={e => setPlan(e.target.value as Subscription['plan'])}
            >
              <option value="TRIAL">تجريبي</option>
              <option value="BASIC">أساسي</option>
              <option value="PROFESSIONAL">احترافي</option>
              <option value="ENTERPRISE">مؤسسي</option>
            </select>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <Label>تاريخ البدء</Label>
              <Input type="date" value={startDate} onChange={e => setStartDate(e.target.value)} className="mt-1" />
            </div>
            <div>
              <Label>تاريخ الانتهاء</Label>
              <Input type="date" value={endDate} onChange={e => setEndDate(e.target.value)} className="mt-1" />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <Label>حد المستخدمين</Label>
              <Input type="number" value={maxUsers} onChange={e => setMaxUsers(e.target.value)} className="mt-1" />
            </div>
            <div>
              <Label>حد أوامر العمل/شهر</Label>
              <Input type="number" value={maxWO} onChange={e => setMaxWO(e.target.value)} className="mt-1" />
            </div>
          </div>
          <div className="flex items-center gap-2">
            <input type="checkbox" id="active" checked={active} onChange={e => setActive(e.target.checked)}
              className="h-4 w-4 rounded border-input" />
            <Label htmlFor="active">نشط</Label>
          </div>
          <div>
            <Label>ملاحظات</Label>
            <Input value={notes} onChange={e => setNotes(e.target.value)} className="mt-1" placeholder="اختياري" />
          </div>
        </div>
        <DialogFooter className="gap-2">
          <Button variant="outline" onClick={onClose} disabled={saving}>إلغاء</Button>
          <Button onClick={handleSave} disabled={saving}>
            {saving ? 'جاري الحفظ...' : 'حفظ'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// ── Main page ─────────────────────────────────────────────
export default function SubscriptionsPage() {
  const { user, lang } = useAuthStore();
  const tr = useT(lang);

  const [subs, setSubs] = useState<Subscription[]>([]);
  const [centers, setCenters] = useState<Center[]>([]);
  const [loading, setLoading] = useState(true);

  const [showAdd, setShowAdd] = useState(false);
  const [editing, setEditing] = useState<Subscription | null>(null);
  const [renewing, setRenewing] = useState<Subscription | null>(null);
  const [search, setSearch] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [subsRes, centersRes] = await Promise.all([
        subscriptionApi.getAll(),
        centerApi.getAll(),
      ]);
      setSubs(subsRes.data);
      setCenters(centersRes.data);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  if (user?.role !== 'system_admin') {
    return (
      <DashboardLayout>
        <div className="flex items-center justify-center h-64 text-muted-foreground">
          غير مصرح لك بالوصول إلى هذه الصفحة
        </div>
      </DashboardLayout>
    );
  }

  const existingCenterIds = new Set(subs.map(s => s.centerId));

  const filtered = subs.filter(s =>
    s.centerName.toLowerCase().includes(search.toLowerCase()) ||
    s.centerNameAr.includes(search) ||
    s.centerCode.toLowerCase().includes(search.toLowerCase())
  );

  // Stats
  const totalActive  = subs.filter(s => s.valid).length;
  const totalExpired = subs.filter(s => s.expired).length;
  const totalRevoked = subs.filter(s => !s.active).length;
  const expiringSoon = subs.filter(s => s.valid && s.daysRemaining !== -1 && s.daysRemaining <= 30).length;

  async function handleRevoke(sub: Subscription) {
    if (!confirm(`هل تريد إلغاء اشتراك "${sub.centerNameAr || sub.centerName}"؟`)) return;
    await subscriptionApi.revoke(sub.id);
    load();
  }

  return (
    <DashboardLayout>
      <div className="p-6 space-y-6">
        {/* Header */}
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <CreditCard className="text-primary" size={28} />
            <div>
              <h1 className="text-2xl font-bold text-foreground">إدارة الاشتراكات</h1>
              <p className="text-sm text-muted-foreground">إدارة اشتراكات المراكز في النظام</p>
            </div>
          </div>
          <Button onClick={() => setShowAdd(true)} className="flex items-center gap-2">
            <Plus size={16} />
            إضافة اشتراك
          </Button>
        </div>

        {/* Stats */}
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <Card>
            <CardContent className="p-4 flex items-center gap-3">
              <div className="p-2 bg-green-100 rounded-lg">
                <CheckCircle className="text-green-600" size={20} />
              </div>
              <div>
                <p className="text-xs text-muted-foreground">سارية</p>
                <p className="text-2xl font-bold text-foreground">{totalActive}</p>
              </div>
            </CardContent>
          </Card>
          <Card>
            <CardContent className="p-4 flex items-center gap-3">
              <div className="p-2 bg-red-100 rounded-lg">
                <AlertCircle className="text-red-600" size={20} />
              </div>
              <div>
                <p className="text-xs text-muted-foreground">منتهية</p>
                <p className="text-2xl font-bold text-foreground">{totalExpired}</p>
              </div>
            </CardContent>
          </Card>
          <Card>
            <CardContent className="p-4 flex items-center gap-3">
              <div className="p-2 bg-orange-100 rounded-lg">
                <Clock className="text-orange-600" size={20} />
              </div>
              <div>
                <p className="text-xs text-muted-foreground">تنتهي قريباً</p>
                <p className="text-2xl font-bold text-foreground">{expiringSoon}</p>
              </div>
            </CardContent>
          </Card>
          <Card>
            <CardContent className="p-4 flex items-center gap-3">
              <div className="p-2 bg-gray-100 rounded-lg">
                <XCircle className="text-gray-600" size={20} />
              </div>
              <div>
                <p className="text-xs text-muted-foreground">ملغاة</p>
                <p className="text-2xl font-bold text-foreground">{totalRevoked}</p>
              </div>
            </CardContent>
          </Card>
        </div>

        {/* Table */}
        <Card>
          <CardHeader className="pb-3">
            <div className="flex items-center justify-between gap-4">
              <CardTitle className="text-base">قائمة الاشتراكات ({filtered.length})</CardTitle>
              <input
                className="px-3 py-1.5 rounded-md border border-input bg-background text-sm w-56"
                placeholder="بحث بالمركز..."
                value={search}
                onChange={e => setSearch(e.target.value)}
              />
            </div>
          </CardHeader>
          <CardContent className="p-0">
            {loading ? (
              <div className="p-8 text-center text-muted-foreground">{tr.loading}</div>
            ) : filtered.length === 0 ? (
              <div className="p-8 text-center text-muted-foreground">{tr.noData}</div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-border bg-muted/40 text-muted-foreground text-right">
                      <th className="px-4 py-3 font-medium">المركز</th>
                      <th className="px-4 py-3 font-medium">الخطة</th>
                      <th className="px-4 py-3 font-medium">البدء</th>
                      <th className="px-4 py-3 font-medium">الانتهاء</th>
                      <th className="px-4 py-3 font-medium">
                        <span className="flex items-center gap-1"><Users size={13} /> الحد</span>
                      </th>
                      <th className="px-4 py-3 font-medium">
                        <span className="flex items-center gap-1"><ClipboardList size={13} /> أوامر/شهر</span>
                      </th>
                      <th className="px-4 py-3 font-medium">الحالة</th>
                      <th className="px-4 py-3 font-medium">إجراءات</th>
                    </tr>
                  </thead>
                  <tbody>
                    {filtered.map((sub, i) => (
                      <tr
                        key={sub.id}
                        className={`border-b border-border hover:bg-muted/30 transition-colors ${
                          i % 2 === 0 ? '' : 'bg-muted/10'
                        }`}
                      >
                        <td className="px-4 py-3">
                          <p className="font-medium text-foreground">{sub.centerNameAr || sub.centerName}</p>
                          <p className="text-xs text-muted-foreground">{sub.centerCode}</p>
                        </td>
                        <td className="px-4 py-3">
                          <span className={`inline-block px-2 py-0.5 rounded-full text-xs font-medium border ${PLAN_COLORS[sub.plan]}`}>
                            {sub.planLabelAr}
                          </span>
                        </td>
                        <td className="px-4 py-3 text-muted-foreground">{sub.startDate}</td>
                        <td className="px-4 py-3 text-muted-foreground">
                          {sub.endDate || <span className="text-green-600 text-xs">بلا انتهاء</span>}
                        </td>
                        <td className="px-4 py-3 text-center">
                          {sub.maxUsers === -1 ? (
                            <span className="text-xs text-green-600">∞</span>
                          ) : sub.maxUsers}
                        </td>
                        <td className="px-4 py-3 text-center">
                          {sub.maxWorkOrdersPerMonth === -1 ? (
                            <span className="text-xs text-green-600">∞</span>
                          ) : sub.maxWorkOrdersPerMonth}
                        </td>
                        <td className="px-4 py-3">
                          <StatusBadge sub={sub} />
                        </td>
                        <td className="px-4 py-3">
                          <div className="flex items-center gap-1">
                            <button
                              onClick={() => setEditing(sub)}
                              className="p-1.5 rounded hover:bg-muted text-muted-foreground hover:text-foreground transition-colors"
                              title="تعديل"
                            >
                              ✏️
                            </button>
                            <button
                              onClick={() => setRenewing(sub)}
                              className="p-1.5 rounded hover:bg-muted text-muted-foreground hover:text-foreground transition-colors"
                              title="تجديد"
                            >
                              <RefreshCw size={14} />
                            </button>
                            <button
                              onClick={() => handleRevoke(sub)}
                              className="p-1.5 rounded hover:bg-red-50 text-muted-foreground hover:text-red-600 transition-colors"
                              title="إلغاء"
                            >
                              <Trash2 size={14} />
                            </button>
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

      {showAdd && (
        <AddDialog
          centers={centers}
          existingCenterIds={existingCenterIds}
          onClose={() => setShowAdd(false)}
          onSaved={() => { setShowAdd(false); load(); }}
        />
      )}
      {editing && (
        <EditDialog
          sub={editing}
          onClose={() => setEditing(null)}
          onSaved={() => { setEditing(null); load(); }}
        />
      )}
      {renewing && (
        <RenewDialog
          sub={renewing}
          onClose={() => setRenewing(null)}
          onSaved={() => { setRenewing(null); load(); }}
        />
      )}
    </DashboardLayout>
  );
}
