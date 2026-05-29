'use client';

import { Suspense, useEffect, useState } from 'react';
import { useAuthStore } from '@/store/authStore';
import { useT } from '@/lib/i18n';
import { meApi } from '@/lib/api';
import { Button } from '@/components/ui/button';
import { User, Shield, Palette, Check, AlertCircle } from 'lucide-react';
import ThemeSwitcher from '@/components/ui/ThemeSwitcher';
import { useSearchParams } from 'next/navigation';

type Tab = 'profile' | 'security' | 'appearance';

interface ProfileForm {
  fullName: string;
  fullNameAr: string;
  email: string;
  phone: string;
}

interface PasswordForm {
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
}

interface Alert {
  type: 'success' | 'error';
  message: string;
}

function SettingsPage() {
  const { user, lang, setLang } = useAuthStore();
  const tr = useT(lang);
  const searchParams = useSearchParams();
  const initialTab = (searchParams.get('tab') as Tab) || 'profile';
  const [tab, setTab] = useState<Tab>(initialTab);
  const [alert, setAlert] = useState<Alert | null>(null);
  const [saving, setSaving] = useState(false);

  const [profile, setProfile] = useState<ProfileForm>({
    fullName: '', fullNameAr: '', email: '', phone: '',
  });

  const [pwd, setPwd] = useState<PasswordForm>({
    currentPassword: '', newPassword: '', confirmPassword: '',
  });

  useEffect(() => {
    meApi.get().then(res => {
      const d = res.data;
      setProfile({
        fullName:   d.fullName  || '',
        fullNameAr: d.fullNameAr || '',
        email:      d.email && !d.email.endsWith('@smms.local') ? d.email : '',
        phone:      d.phone || '',
      });
    }).catch(() => {});
  }, []);

  const showAlert = (type: Alert['type'], message: string) => {
    setAlert({ type, message });
    setTimeout(() => setAlert(null), 4000);
  };

  const saveProfile = async () => {
    setSaving(true);
    try {
      await meApi.update({
        fullName:   profile.fullName   || undefined,
        fullNameAr: profile.fullNameAr || undefined,
        email:      profile.email      || undefined,
        phone:      profile.phone      || undefined,
      });
      showAlert('success', lang === 'ar' ? 'تم حفظ الملف الشخصي بنجاح' : 'Profile saved successfully');
    } catch {
      showAlert('error', lang === 'ar' ? 'فشل حفظ البيانات' : 'Failed to save profile');
    } finally {
      setSaving(false);
    }
  };

  const changePassword = async () => {
    if (pwd.newPassword !== pwd.confirmPassword) {
      showAlert('error', lang === 'ar' ? 'كلمتا المرور غير متطابقتين' : 'Passwords do not match');
      return;
    }
    if (pwd.newPassword.length < 8) {
      showAlert('error', lang === 'ar' ? 'كلمة المرور يجب أن تكون 8 أحرف على الأقل' : 'Password must be at least 8 characters');
      return;
    }
    setSaving(true);
    try {
      await meApi.changePassword(pwd.currentPassword, pwd.newPassword);
      setPwd({ currentPassword: '', newPassword: '', confirmPassword: '' });
      showAlert('success', lang === 'ar' ? 'تم تغيير كلمة المرور بنجاح' : 'Password changed successfully');
    } catch (e: unknown) {
      const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message;
      showAlert('error', msg || (lang === 'ar' ? 'فشل تغيير كلمة المرور' : 'Failed to change password'));
    } finally {
      setSaving(false);
    }
  };

  const tabs: { key: Tab; labelAr: string; labelEn: string; icon: React.ReactNode }[] = [
    { key: 'profile',    labelAr: 'الملف الشخصي', labelEn: 'Profile',    icon: <User size={16} /> },
    { key: 'security',   labelAr: 'الأمان',        labelEn: 'Security',   icon: <Shield size={16} /> },
    { key: 'appearance', labelAr: 'المظهر',         labelEn: 'Appearance', icon: <Palette size={16} /> },
  ];

  const inputClass = 'w-full rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring';
  const labelClass = 'block text-sm font-medium text-foreground mb-1';

  return (
    <div className="max-w-3xl mx-auto px-4 py-6">
      <h1 className="text-xl font-bold text-foreground mb-6">
        {lang === 'ar' ? 'الإعدادات' : 'Settings'}
      </h1>

      {/* Alert */}
      {alert && (
        <div className={`flex items-center gap-2 p-3 rounded-md mb-4 text-sm ${
          alert.type === 'success'
            ? 'bg-green-50 text-green-800 border border-green-200 dark:bg-green-900/20 dark:text-green-400 dark:border-green-800'
            : 'bg-red-50 text-red-800 border border-red-200 dark:bg-red-900/20 dark:text-red-400 dark:border-red-800'
        }`}>
          {alert.type === 'success' ? <Check size={15} /> : <AlertCircle size={15} />}
          {alert.message}
        </div>
      )}

      <div className="flex gap-6">
        {/* Sidebar tabs */}
        <nav className="w-44 shrink-0 space-y-1">
          {tabs.map(t => (
            <button
              key={t.key}
              onClick={() => { setTab(t.key); setAlert(null); }}
              className={`w-full flex items-center gap-2 px-3 py-2 rounded-md text-sm font-medium transition-colors ${
                tab === t.key
                  ? 'bg-primary text-primary-foreground'
                  : 'text-muted-foreground hover:bg-accent hover:text-foreground'
              }`}
            >
              {t.icon}
              {lang === 'ar' ? t.labelAr : t.labelEn}
            </button>
          ))}
        </nav>

        {/* Content */}
        <div className="flex-1 bg-card border border-border rounded-lg p-6">

          {/* ── Profile Tab ─────────────────────────────── */}
          {tab === 'profile' && (
            <div className="space-y-5">
              <div>
                <h2 className="text-base font-semibold text-foreground mb-1">
                  {lang === 'ar' ? 'الملف الشخصي' : 'Profile'}
                </h2>
                <p className="text-xs text-muted-foreground">
                  {lang === 'ar' ? 'اسم المستخدم: ' : 'Username: '}
                  <span className="font-medium text-foreground">{user?.username}</span>
                  <span className="mx-2">·</span>
                  {lang === 'ar' ? 'الدور: ' : 'Role: '}
                  <span className="font-medium text-foreground">{user?.role}</span>
                </p>
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div>
                  <label className={labelClass}>{lang === 'ar' ? 'الاسم الكامل (عربي)' : 'Full Name (Arabic)'}</label>
                  <input
                    dir="rtl"
                    className={inputClass}
                    value={profile.fullNameAr}
                    onChange={e => setProfile(p => ({ ...p, fullNameAr: e.target.value }))}
                    placeholder="الاسم بالعربية"
                  />
                </div>
                <div>
                  <label className={labelClass}>{lang === 'ar' ? 'الاسم الكامل (إنجليزي)' : 'Full Name (English)'}</label>
                  <input
                    dir="ltr"
                    className={inputClass}
                    value={profile.fullName}
                    onChange={e => setProfile(p => ({ ...p, fullName: e.target.value }))}
                    placeholder="Full name in English"
                  />
                </div>
                <div>
                  <label className={labelClass}>{lang === 'ar' ? 'البريد الإلكتروني' : 'Email'}</label>
                  <input
                    type="email"
                    dir="ltr"
                    className={inputClass}
                    value={profile.email}
                    onChange={e => setProfile(p => ({ ...p, email: e.target.value }))}
                    placeholder="you@example.com"
                  />
                </div>
                <div>
                  <label className={labelClass}>{lang === 'ar' ? 'رقم الجوال' : 'Phone'}</label>
                  <input
                    type="tel"
                    dir="ltr"
                    className={inputClass}
                    value={profile.phone}
                    onChange={e => setProfile(p => ({ ...p, phone: e.target.value }))}
                    placeholder="+966 5x xxx xxxx"
                  />
                </div>
              </div>

              <div className="flex justify-end pt-2">
                <Button onClick={saveProfile} disabled={saving} size="sm">
                  {saving
                    ? (lang === 'ar' ? 'جاري الحفظ...' : 'Saving...')
                    : tr.save}
                </Button>
              </div>
            </div>
          )}

          {/* ── Security Tab ────────────────────────────── */}
          {tab === 'security' && (
            <div className="space-y-5">
              <div>
                <h2 className="text-base font-semibold text-foreground mb-1">
                  {lang === 'ar' ? 'تغيير كلمة المرور' : 'Change Password'}
                </h2>
                <p className="text-xs text-muted-foreground">
                  {lang === 'ar' ? 'يُنصح بتغيير كلمة المرور بشكل دوري لحماية حسابك' : 'It is recommended to change your password periodically'}
                </p>
              </div>

              <div className="space-y-4 max-w-sm">
                <div>
                  <label className={labelClass}>{lang === 'ar' ? 'كلمة المرور الحالية' : 'Current Password'}</label>
                  <input
                    type="password"
                    dir="ltr"
                    className={inputClass}
                    value={pwd.currentPassword}
                    onChange={e => setPwd(p => ({ ...p, currentPassword: e.target.value }))}
                    placeholder="••••••••"
                  />
                </div>
                <div>
                  <label className={labelClass}>{lang === 'ar' ? 'كلمة المرور الجديدة' : 'New Password'}</label>
                  <input
                    type="password"
                    dir="ltr"
                    className={inputClass}
                    value={pwd.newPassword}
                    onChange={e => setPwd(p => ({ ...p, newPassword: e.target.value }))}
                    placeholder="••••••••"
                  />
                  <p className="text-xs text-muted-foreground mt-1">
                    {lang === 'ar' ? 'الحد الأدنى 8 أحرف' : 'Minimum 8 characters'}
                  </p>
                </div>
                <div>
                  <label className={labelClass}>{lang === 'ar' ? 'تأكيد كلمة المرور' : 'Confirm Password'}</label>
                  <input
                    type="password"
                    dir="ltr"
                    className={inputClass}
                    value={pwd.confirmPassword}
                    onChange={e => setPwd(p => ({ ...p, confirmPassword: e.target.value }))}
                    placeholder="••••••••"
                  />
                </div>
              </div>

              <div className="flex justify-end pt-2">
                <Button
                  onClick={changePassword}
                  disabled={saving || !pwd.currentPassword || !pwd.newPassword || !pwd.confirmPassword}
                  size="sm"
                >
                  {saving
                    ? (lang === 'ar' ? 'جاري الحفظ...' : 'Saving...')
                    : (lang === 'ar' ? 'تغيير كلمة المرور' : 'Change Password')}
                </Button>
              </div>
            </div>
          )}

          {/* ── Appearance Tab ───────────────────────────── */}
          {tab === 'appearance' && (
            <div className="space-y-6">
              <div>
                <h2 className="text-base font-semibold text-foreground mb-1">
                  {lang === 'ar' ? 'المظهر' : 'Appearance'}
                </h2>
                <p className="text-xs text-muted-foreground">
                  {lang === 'ar' ? 'خصّص مظهر التطبيق حسب تفضيلاتك' : 'Customize the appearance to your preference'}
                </p>
              </div>

              {/* Theme */}
              <div className="flex items-center justify-between py-3 border-b border-border">
                <div>
                  <p className="text-sm font-medium text-foreground">
                    {lang === 'ar' ? 'السمة' : 'Theme'}
                  </p>
                  <p className="text-xs text-muted-foreground">
                    {lang === 'ar' ? 'فاتح / داكن / تلقائي' : 'Light / Dark / System'}
                  </p>
                </div>
                <ThemeSwitcher />
              </div>

              {/* Language */}
              <div className="flex items-center justify-between py-3 border-b border-border">
                <div>
                  <p className="text-sm font-medium text-foreground">
                    {lang === 'ar' ? 'اللغة' : 'Language'}
                  </p>
                  <p className="text-xs text-muted-foreground">
                    {lang === 'ar' ? 'عربي / إنجليزي' : 'Arabic / English'}
                  </p>
                </div>
                <div className="flex gap-2">
                  <button
                    onClick={() => setLang('ar')}
                    className={`px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
                      lang === 'ar'
                        ? 'bg-primary text-primary-foreground'
                        : 'border border-border text-muted-foreground hover:bg-accent'
                    }`}
                  >
                    عربي
                  </button>
                  <button
                    onClick={() => setLang('en')}
                    className={`px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
                      lang === 'en'
                        ? 'bg-primary text-primary-foreground'
                        : 'border border-border text-muted-foreground hover:bg-accent'
                    }`}
                  >
                    English
                  </button>
                </div>
              </div>

              {/* Direction indicator */}
              <div className="flex items-center justify-between py-3">
                <div>
                  <p className="text-sm font-medium text-foreground">
                    {lang === 'ar' ? 'اتجاه النص' : 'Text Direction'}
                  </p>
                  <p className="text-xs text-muted-foreground">
                    {lang === 'ar' ? 'يتبع اللغة المختارة تلقائياً' : 'Follows selected language automatically'}
                  </p>
                </div>
                <span className="text-sm font-medium text-primary">
                  {lang === 'ar' ? 'RTL ←' : '→ LTR'}
                </span>
              </div>
            </div>
          )}

        </div>
      </div>
    </div>
  );
}

export default function SettingsPageWrapper() {
  return (
    <Suspense>
      <SettingsPage />
    </Suspense>
  );
}
