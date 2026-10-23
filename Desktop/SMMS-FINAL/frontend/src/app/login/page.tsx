'use client';

import { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { useAuthStore } from '@/store/authStore';
import { useThemeStore } from '@/store/themeStore';
import { authApi } from '@/lib/api';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Eye, EyeOff, Globe, Lock, User } from 'lucide-react';

export default function LoginPage() {
  const router = useRouter();
  const { setAuth, lang, setLang } = useAuthStore();
  const { init: initTheme } = useThemeStore();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');

  useEffect(() => { initTheme(); }, [initTheme]);
  const [showPass, setShowPass] = useState(false);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const isAr = lang === 'ar';

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!username || !password) {
      setError(isAr ? 'يرجى إدخال اسم المستخدم وكلمة المرور' : 'Please enter username and password');
      return;
    }
    setLoading(true);
    setError('');
    try {
      const res = await authApi.login(username, password);
      const { token, role, userId, username: uname, fullName, fullNameAr, centerId, centerName, centerNameAr } = res.data;
      const user = {
        id:         userId,
        username:   uname || username,
        fullName:   fullName || username,
        fullNameAr: fullNameAr || '',
        email:      '',
        role,
        active:     true,
        ...(centerId ? { center: { id: centerId, name: centerName || '', nameAr: centerNameAr || '', code: '' } } : {}),
      };
      setAuth(user, token);
      if (role === 'technician') router.push('/my-tasks');
      else if (role === 'resident' || role === 'department') router.push('/new-request');
      else router.push('/dashboard');
    } catch {
      setError(isAr ? 'فشل تسجيل الدخول. تحقق من بياناتك.' : 'Login failed. Check your credentials.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div
      className="min-h-screen flex items-center justify-center bg-gradient-to-br from-[#0f2744] via-[#1a3a6b] to-[#0f2744] p-4"
      dir={isAr ? 'rtl' : 'ltr'}
    >
      {/* Lang Toggle */}
      <button
        type="button"
        onClick={() => setLang(isAr ? 'en' : 'ar')}
        className="absolute top-4 right-4 flex items-center gap-1.5 text-white/70 hover:text-white text-sm bg-white/10 px-3 py-1.5 rounded-full transition outline-none focus-visible:ring-2 focus-visible:ring-white/80"
      >
        <Globe size={14} />
        {isAr ? 'English' : 'عربي'}
      </button>

      <div className="w-full max-w-sm">
        {/* Card */}
        <div className="bg-white rounded-2xl shadow-2xl p-8">
          {/* Logo */}
          <div className="text-center mb-8">
            <div className="w-16 h-16 bg-[#0f2744] rounded-2xl flex items-center justify-center mx-auto mb-4 shadow-lg">
              <span className="text-white text-2xl font-bold">S</span>
            </div>
            <h1 className="text-xl font-bold text-gray-900">
              {isAr ? 'نظام إدارة الصيانة' : 'Maintenance Management'}
            </h1>
            <p className="text-sm text-gray-500 mt-1">
              {isAr ? 'تجمع جازان الصحي' : 'Jazan Health Cluster'}
            </p>
          </div>

          <form onSubmit={handleSubmit} className="space-y-5">
            {/* Username */}
            <div className="space-y-1.5">
              <Label htmlFor="username" className="text-sm font-medium text-gray-700">
                {isAr ? 'اسم المستخدم' : 'Username'}
              </Label>
              <div className="relative">
                <User size={16} className={`absolute top-1/2 -translate-y-1/2 text-gray-400 ${isAr ? 'right-3' : 'left-3'}`} />
                <Input
                  id="username"
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  placeholder={isAr ? 'أدخل اسم المستخدم' : 'Enter username'}
                  className={`${isAr ? 'pr-9' : 'pl-9'} h-11`}
                  disabled={loading}
                  autoComplete="username"
                />
              </div>
            </div>

            {/* Password */}
            <div className="space-y-1.5">
              <Label htmlFor="password" className="text-sm font-medium text-gray-700">
                {isAr ? 'كلمة المرور' : 'Password'}
              </Label>
              <div className="relative">
                <Lock size={16} className={`absolute top-1/2 -translate-y-1/2 text-gray-400 ${isAr ? 'right-3' : 'left-3'}`} />
                <Input
                  id="password"
                  type={showPass ? 'text' : 'password'}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder={isAr ? 'أدخل كلمة المرور' : 'Enter password'}
                  className={`${isAr ? 'pr-9 pl-9' : 'pl-9 pr-9'} h-11`}
                  disabled={loading}
                  autoComplete="current-password"
                />
                <button
                  type="button"
                  onClick={() => setShowPass(!showPass)}
                  aria-label={showPass ? (isAr ? 'إخفاء كلمة المرور' : 'Hide password') : (isAr ? 'إظهار كلمة المرور' : 'Show password')}
                  aria-pressed={showPass}
                  className={`absolute top-1/2 -translate-y-1/2 text-gray-500 hover:text-gray-700 rounded outline-none focus-visible:ring-2 focus-visible:ring-ring/50 ${isAr ? 'left-3' : 'right-3'}`}
                >
                  {showPass ? <EyeOff size={16} /> : <Eye size={16} />}
                </button>
              </div>
            </div>

            {/* Error */}
            {error && (
              <div className="bg-red-50 border border-red-200 text-red-700 text-sm px-3 py-2.5 rounded-lg">
                {error}
              </div>
            )}

            {/* Submit */}
            <Button
              type="submit"
              className="w-full h-11 bg-[#0f2744] hover:bg-[#1a3a6b] text-white font-semibold"
              disabled={loading}
            >
              {loading ? (
                <div className="flex items-center gap-2">
                  <div className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin" />
                  {isAr ? 'جاري الدخول...' : 'Signing in...'}
                </div>
              ) : (
                isAr ? 'تسجيل الدخول' : 'Sign In'
              )}
            </Button>
          </form>
        </div>

        <p className="text-center text-white/40 text-xs mt-6">
          SMMS © {new Date().getFullYear()}
        </p>
      </div>
    </div>
  );
}
