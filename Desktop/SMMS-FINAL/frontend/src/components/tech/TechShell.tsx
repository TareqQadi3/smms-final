'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { Bell, ClipboardList, History, Globe, LogOut, WifiOff, CloudUpload } from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { useThemeStore } from '@/store/themeStore';
import { buildLoginUrl, readAccessToken } from '@/lib/api';
import { useModuleT } from '@/lib/module-i18n';
import { useNotificationFeed } from '@/lib/ws';
import { useOfflineQueue } from '@/lib/offline/useOfflineQueue';
import { PermissionDenied } from '@/components/common';
import { techDict } from '@/modules/tech/i18n';
import { cn } from '@/lib/utils';
import { TechContext } from './TechContext';

const ROLE = 'technician';

/**
 * Mobile-first frame of the technician area: compact header (connection, pending actions, alerts,
 * language, sign out), offline banner and a thumb-reachable bottom navigation. Every control is at
 * least 44x44 px. Role gating here is a UI hint; the server enforces access.
 */
export default function TechShell({ children }: { children: React.ReactNode }) {
  const { t, dir, lang } = useModuleT(techDict);
  const { user, init, logout, setLang, sessionExpired } = useAuthStore();
  const leaving = useRef(false);
  const { init: initTheme } = useThemeStore();
  const router = useRouter();
  const pathname = usePathname();
  const { items, online } = useOfflineQueue();
  const [frameTick, setFrameTick] = useState(0);

  useEffect(() => {
    init();
    initTheme();
  }, [init, initTheme]);

  // No session at all: go to /login and come back here afterwards
  useEffect(() => {
    if (user === null && !readAccessToken() && !leaving.current && !sessionExpired) {
      const here = typeof window !== 'undefined' ? window.location.pathname + window.location.search : pathname;
      router.replace(buildLoginUrl(here, false));
    }
  }, [user, router, pathname, sessionExpired]);

  const isTech = user?.role === ROLE;
  const onFrame = useCallback(() => setFrameTick((n) => n + 1), []);
  const feed = useNotificationFeed(isTech, onFrame);

  const ctx = useMemo(
    () => ({ unread: feed.unread, live: feed.live, frameTick, refreshUnread: feed.refresh, markAllRead: feed.markAllRead }),
    [feed.unread, feed.live, feed.refresh, feed.markAllRead, frameTick]
  );

  if (!user) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-background" role="status" aria-busy="true">
        <div className="size-10 animate-spin rounded-full border-4 border-blue-600 border-t-transparent" />
        <span className="sr-only">...</span>
      </div>
    );
  }

  if (!isTech) {
    return (
      <div dir={dir} className="min-h-screen bg-background p-4 text-foreground">
        <PermissionDenied
          title={t('techOnlyTitle')}
          description={t('techOnlyBody')}
          action={<Link href="/" className="inline-flex min-h-11 items-center rounded-lg bg-primary px-4 text-sm font-medium text-primary-foreground">{t('goHome')}</Link>}
        />
      </div>
    );
  }

  const pending = items.filter((i) => i.status !== 'failed').length;
  const failed = items.filter((i) => i.status === 'failed').length;
  const nav = [
    { href: '/tech', label: t('navQueue'), icon: ClipboardList, active: pathname === '/tech' || (pathname.startsWith('/tech/') && !pathname.startsWith('/tech/history') && !pathname.startsWith('/tech/notifications')) },
    { href: '/tech/history', label: t('navHistory'), icon: History, active: pathname.startsWith('/tech/history') },
    { href: '/tech/notifications', label: t('navAlerts'), icon: Bell, active: pathname.startsWith('/tech/notifications'), badge: feed.unread },
  ];

  const iconBtn = 'relative inline-flex size-11 items-center justify-center rounded-lg text-foreground outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50';

  return (
    <TechContext.Provider value={ctx}>
      <div dir={dir} className="min-h-screen bg-background text-foreground">
        <header className="sticky top-0 z-20 flex h-14 items-center gap-1 border-b border-border bg-card px-3">
          <h1 className="min-w-0 flex-1 truncate text-base font-semibold">{t('appTitle')}</h1>

          <span
            role="status"
            className={cn('inline-flex h-8 items-center gap-1 rounded-full px-2.5 text-xs font-medium', online ? 'bg-green-100 text-green-800' : 'bg-amber-100 text-amber-900')}
          >
            {!online && <WifiOff size={14} aria-hidden="true" />}
            {online ? t('online') : t('offline')}
          </span>

          {pending + failed > 0 && (
            <span
              data-testid="pending-badge"
              className={cn('inline-flex h-8 items-center gap-1 rounded-full px-2.5 text-xs font-medium', failed ? 'bg-red-100 text-red-800' : 'bg-blue-100 text-blue-800')}
            >
              <CloudUpload size={14} aria-hidden="true" />
              {failed ? t('failedCount', { n: failed }) : t('pendingCount', { n: pending })}
            </span>
          )}

          <button type="button" className={iconBtn} onClick={() => setLang(lang === 'ar' ? 'en' : 'ar')} aria-label={t('switchLang')}>
            <Globe size={20} aria-hidden="true" />
          </button>
          <button
            type="button"
            className={iconBtn}
            onClick={() => { leaving.current = true; logout(); router.push('/login'); }}
            aria-label={t('logout')}
          >
            <LogOut size={20} aria-hidden="true" />
          </button>
        </header>

        {!online && (
          <div role="status" className="border-b border-amber-300 bg-amber-100 px-4 py-2 text-sm text-amber-900">
            {t('offlineBanner')}
          </div>
        )}

        {user.passwordChangeRequired && (
          <div role="alert" className="flex flex-wrap items-center gap-2 border-b border-amber-300 bg-amber-100 px-4 py-2 text-sm text-amber-900">
            <span>{t('changePasswordBanner')}</span>
            <Link href="/settings?tab=security" className="inline-flex min-h-11 items-center font-semibold underline">
              {t('changePassword')}
            </Link>
          </div>
        )}

        <main className="mx-auto w-full max-w-2xl px-3 pb-28 pt-4">{children}</main>

        <nav
          aria-label={t('navLabel')}
          className="fixed inset-x-0 bottom-0 z-20 border-t border-border bg-card pb-[env(safe-area-inset-bottom)]"
        >
          <ul className="mx-auto flex max-w-2xl">
            {nav.map(({ href, label, icon: Icon, active, badge }) => (
              <li key={href} className="flex-1">
                <Link
                  href={href}
                  aria-current={active ? 'page' : undefined}
                  className={cn(
                    'relative flex min-h-14 flex-col items-center justify-center gap-0.5 px-2 text-xs font-medium outline-none focus-visible:ring-3 focus-visible:ring-ring/50',
                    active ? 'text-primary' : 'text-muted-foreground'
                  )}
                >
                  <Icon size={22} aria-hidden="true" />
                  <span>{label}</span>
                  {badge ? (
                    <span
                      className="absolute top-1.5 ms-6 inline-flex min-w-5 items-center justify-center rounded-full bg-red-600 px-1 text-[11px] font-bold leading-5 text-white"
                      aria-label={t('unreadCount', { n: badge })}
                    >
                      {badge > 99 ? '99+' : badge}
                    </span>
                  ) : null}
                </Link>
              </li>
            ))}
          </ul>
        </nav>
      </div>
    </TechContext.Provider>
  );
}
