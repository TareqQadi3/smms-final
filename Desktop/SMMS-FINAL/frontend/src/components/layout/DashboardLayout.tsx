'use client';

import { useState, useEffect } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useAuthStore } from '@/store/authStore';
import { useThemeStore } from '@/store/themeStore';
import Sidebar from './Sidebar';
import Topbar from './Topbar';
import { cn } from '@/lib/utils';

export default function DashboardLayout({ children }: { children: React.ReactNode }) {
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const { user, lang, init } = useAuthStore();
  const { init: initTheme } = useThemeStore();
  const router = useRouter();

  useEffect(() => {
    init();
    initTheme();
  }, [init, initTheme]);

  useEffect(() => {
    if (user === null) {
      const token = localStorage.getItem('smms_token');
      if (!token) router.replace('/login');
    }
  }, [user, router]);

  if (!user) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-background">
        <div className="w-10 h-10 border-4 border-blue-600 border-t-transparent rounded-full animate-spin" />
      </div>
    );
  }

  return (
    <div dir={lang === 'ar' ? 'rtl' : 'ltr'} className="min-h-screen bg-background text-foreground">
      <Sidebar open={sidebarOpen} onClose={() => setSidebarOpen(false)} />
      <div className={cn('transition-all duration-300', lang === 'ar' ? 'lg:mr-64' : 'lg:ml-64')}>
        <Topbar onMenuClick={() => setSidebarOpen(true)} />
        {user.passwordChangeRequired && (
          <div role="alert" data-testid="password-change-banner" className="bg-amber-100 text-amber-900 border-b border-amber-300 px-4 py-2 text-sm flex flex-wrap items-center gap-2">
            <span>
              {lang === 'ar'
                ? 'حسابك يستخدم كلمة مرور افتراضية. يرجى تغييرها الآن.'
                : 'Your account is using a default password. Please change it now.'}
            </span>
            <Link href="/settings?tab=security" className="font-semibold underline">
              {lang === 'ar' ? 'تغيير كلمة المرور' : 'Change password'}
            </Link>
          </div>
        )}
        <main className="p-4 md:p-6">{children}</main>
      </div>
    </div>
  );
}
