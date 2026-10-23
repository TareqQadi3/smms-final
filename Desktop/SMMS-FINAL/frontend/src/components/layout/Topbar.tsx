'use client';

import { useEffect, useRef, useState, useCallback } from 'react';
import { useAuthStore } from '@/store/authStore';
import { useT, roleLabels } from '@/lib/i18n';
import { notificationApi } from '@/lib/api';
import { useRouter } from 'next/navigation';
import { Menu, Bell, Globe, LogOut, User, Settings, Check, AlertCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import ThemeSwitcher from '@/components/ui/ThemeSwitcher';
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem,
  DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';

interface Notification {
  id: string;
  title: string;
  body?: string;
  type: string;
  read: boolean;
  createdAt: string;
}

interface TopbarProps {
  onMenuClick: () => void;
}

export default function Topbar({ onMenuClick }: TopbarProps) {
  const { user, lang, setLang, logout } = useAuthStore();
  const tr = useT(lang);
  const router = useRouter();

  const [unreadCount, setUnreadCount]   = useState(0);
  const [notifications, setNotifications] = useState<Notification[]>([]);
  const [notifOpen, setNotifOpen]       = useState(false);
  const notifRef = useRef<HTMLDivElement>(null);

  const fetchUnread = useCallback(() => {
    notificationApi.unreadCount()
      .then(res => setUnreadCount(Number(res.data) || 0))
      .catch(() => {});
  }, []);

  const fetchNotifications = useCallback(() => {
    notificationApi.list(0, 10)
      .then(res => {
        const data = res.data;
        setNotifications(Array.isArray(data) ? data : data?.content ?? []);
      })
      .catch(() => {});
  }, []);

  useEffect(() => {
    fetchUnread();
    const interval = setInterval(fetchUnread, 30_000);
    return () => clearInterval(interval);
  }, [fetchUnread]);

  useEffect(() => {
    if (notifOpen) fetchNotifications();
  }, [notifOpen, fetchNotifications]);

  // Close panel on outside click
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (notifRef.current && !notifRef.current.contains(e.target as Node)) {
        setNotifOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, []);

  const markAllRead = async () => {
    await notificationApi.readAll().catch(() => {});
    setUnreadCount(0);
    setNotifications(prev => prev.map(n => ({ ...n, read: true })));
  };

  const handleLogout = () => { logout(); router.push('/login'); };
  const toggleLang   = () => setLang(lang === 'ar' ? 'en' : 'ar');

  const timeAgo = (iso: string) => {
    const diff = Date.now() - new Date(iso).getTime();
    const m = Math.floor(diff / 60000);
    if (m < 1)  return lang === 'ar' ? 'الآن' : 'just now';
    if (m < 60) return lang === 'ar' ? `${m} د` : `${m}m`;
    const h = Math.floor(m / 60);
    if (h < 24) return lang === 'ar' ? `${h} س` : `${h}h`;
    return lang === 'ar' ? `${Math.floor(h / 24)} ي` : `${Math.floor(h / 24)}d`;
  };

  return (
    <header className="h-14 bg-card border-b border-border flex items-center justify-between px-4 sticky top-0 z-10">
      {/* Left */}
      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={onMenuClick}
          aria-label={lang === 'ar' ? 'فتح القائمة' : 'Open menu'}
          className="lg:hidden inline-flex size-10 items-center justify-center rounded-md hover:bg-accent text-muted-foreground focus-visible:ring-3 focus-visible:ring-ring/50 outline-none"
        >
          <Menu size={20} aria-hidden="true" />
        </button>
        <h1 className="text-sm font-semibold text-foreground hidden sm:block">
          {tr.appName}
        </h1>
      </div>

      {/* Right */}
      <div className="flex items-center gap-1 sm:gap-2">

        {/* Theme Switcher */}
        <ThemeSwitcher />

        {/* Language */}
        <Button variant="ghost" size="sm" onClick={toggleLang} aria-label={lang === 'ar' ? 'Switch to English' : 'التبديل إلى العربية'} className="gap-1.5 text-xs font-medium">
          <Globe size={15} />
          {lang === 'ar' ? 'EN' : 'عربي'}
        </Button>

        {/* Notifications */}
        <div className="relative" ref={notifRef}>
          <Button
            variant="ghost"
            size="icon"
            className="relative"
            onClick={() => setNotifOpen(o => !o)}
            aria-label={lang === 'ar' ? 'الإشعارات' : 'Notifications'}
            aria-expanded={notifOpen}
          >
            <Bell size={17} />
            {unreadCount > 0 && (
              <span className="absolute top-1 end-1 min-w-[16px] h-4 bg-destructive rounded-full text-[10px] font-bold text-white flex items-center justify-center px-0.5">
                {unreadCount > 99 ? '99+' : unreadCount}
              </span>
            )}
          </Button>

          {/* Notification panel */}
          {notifOpen && (
            <div className="absolute end-0 top-10 w-80 bg-popover border border-border rounded-lg shadow-lg z-50">
              <div className="flex items-center justify-between px-4 py-3 border-b border-border">
                <span className="text-sm font-semibold text-foreground">
                  {lang === 'ar' ? 'الإشعارات' : 'Notifications'}
                  {unreadCount > 0 && (
                    <span className="ms-2 bg-destructive text-white text-xs rounded-full px-1.5 py-0.5">
                      {unreadCount}
                    </span>
                  )}
                </span>
                {unreadCount > 0 && (
                  <button
                    onClick={markAllRead}
                    className="text-xs text-primary hover:underline flex items-center gap-1"
                  >
                    <Check size={12} />
                    {lang === 'ar' ? 'تعليم الكل كمقروء' : 'Mark all read'}
                  </button>
                )}
              </div>

              <div className="max-h-72 overflow-y-auto divide-y divide-border">
                {notifications.length === 0 ? (
                  <div className="flex flex-col items-center justify-center py-8 text-muted-foreground gap-2">
                    <AlertCircle size={24} />
                    <span className="text-sm">
                      {lang === 'ar' ? 'لا توجد إشعارات' : 'No notifications'}
                    </span>
                  </div>
                ) : (
                  notifications.map(n => (
                    <div
                      key={n.id}
                      className={`px-4 py-3 flex gap-3 items-start transition-colors ${
                        !n.read ? 'bg-primary/5' : ''
                      }`}
                    >
                      <div className={`mt-0.5 w-2 h-2 rounded-full shrink-0 ${!n.read ? 'bg-primary' : 'bg-transparent'}`} />
                      <div className="flex-1 min-w-0">
                        <p className="text-sm text-foreground leading-snug">
                          {n.title}
                        </p>
                        {n.body && (
                          <p className="text-xs text-muted-foreground mt-0.5 leading-snug">{n.body}</p>
                        )}
                        <p className="text-xs text-muted-foreground mt-0.5">
                          {timeAgo(n.createdAt)}
                        </p>
                      </div>
                    </div>
                  ))
                )}
              </div>
            </div>
          )}
        </div>

        {/* User Menu */}
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" size="sm" className="gap-2">
              <div className="w-7 h-7 rounded-full bg-primary flex items-center justify-center text-primary-foreground text-xs font-bold">
                {user?.fullName?.charAt(0) || 'U'}
              </div>
              <span className="text-xs font-medium hidden sm:block max-w-[120px] truncate">
                {user?.fullName}
              </span>
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align={lang === 'ar' ? 'start' : 'end'} className="w-52">
            <div className="px-3 py-2">
              <p className="text-sm font-semibold">{user?.fullName}</p>
              <p className="text-xs text-muted-foreground">
                {user?.role ? roleLabels[user.role]?.[lang] : ''}
              </p>
            </div>
            <DropdownMenuSeparator />
            <DropdownMenuItem
              className="gap-2 cursor-pointer"
              onClick={() => router.push('/settings')}
            >
              <User size={15} /> {lang === 'ar' ? 'الملف الشخصي' : 'Profile'}
            </DropdownMenuItem>
            <DropdownMenuItem
              className="gap-2 cursor-pointer"
              onClick={() => router.push('/settings?tab=security')}
            >
              <Settings size={15} /> {lang === 'ar' ? 'الإعدادات' : 'Settings'}
            </DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={handleLogout} className="gap-2 text-destructive cursor-pointer focus:text-destructive">
              <LogOut size={15} /> {tr.logout}
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </header>
  );
}
