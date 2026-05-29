'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { cn } from '@/lib/utils';
import { useAuthStore } from '@/store/authStore';
import { useT } from '@/lib/i18n';
import { UserRole } from '@/lib/types';
import {
  LayoutDashboard, ClipboardList, Users, MapPin, Building2,
  Radio, CheckSquare, BarChart3, Settings, Wrench, PlusCircle, X, SlidersHorizontal
} from 'lucide-react';

interface NavItem {
  href: string;
  icon: React.ReactNode;
  label: string;
  roles: UserRole[];
}

interface SidebarProps {
  open: boolean;
  onClose: () => void;
}

export default function Sidebar({ open, onClose }: SidebarProps) {
  const pathname = usePathname();
  const { user, lang } = useAuthStore();
  const tr = useT(lang);
  const isAr = lang === 'ar';

  const navItems: NavItem[] = [
    {
      href: '/dashboard',
      icon: <LayoutDashboard size={20} />,
      label: tr.dashboard,
      roles: ['system_admin','region_supervisor','hospital_supervisor','control_room',
              'maintenance_manager','electrical_manager','operating_company_manager'],
    },
    {
      href: '/work-orders',
      icon: <ClipboardList size={20} />,
      label: tr.workOrders,
      roles: ['system_admin','region_supervisor','hospital_supervisor','control_room',
              'maintenance_manager','electrical_manager','warehouse'],
    },
    {
      href: '/my-tasks',
      icon: <Wrench size={20} />,
      label: tr.myTasks,
      roles: ['technician'],
    },
    {
      href: '/new-request',
      icon: <PlusCircle size={20} />,
      label: tr.newRequest,
      roles: ['resident','department'],
    },
    {
      href: '/approvals',
      icon: <CheckSquare size={20} />,
      label: tr.approvals,
      roles: ['system_admin','region_supervisor','hospital_supervisor',
              'maintenance_manager','electrical_manager','operating_company_manager'],
    },
    {
      href: '/users',
      icon: <Users size={20} />,
      label: tr.users,
      roles: ['system_admin','region_supervisor','hospital_supervisor'],
    },
    {
      href: '/regions',
      icon: <MapPin size={20} />,
      label: tr.regions,
      roles: ['system_admin'],
    },
    {
      href: '/centers',
      icon: <Building2 size={20} />,
      label: tr.centers,
      roles: ['system_admin','region_supervisor'],
    },
    {
      href: '/control-rooms',
      icon: <Radio size={20} />,
      label: tr.controlRooms,
      roles: ['system_admin','region_supervisor','hospital_supervisor'],
    },
    {
      href: '/reports',
      icon: <BarChart3 size={20} />,
      label: tr.reports,
      roles: ['system_admin','region_supervisor','hospital_supervisor',
              'maintenance_manager','electrical_manager','operating_company_manager'],
    },
    {
      href: '/settings',
      icon: <Settings size={20} />,
      label: tr.settings,
      roles: ['system_admin','region_supervisor','hospital_supervisor','control_room',
              'technician','maintenance_manager','electrical_manager','warehouse',
              'operating_company_manager','resident','department'],
    },
    {
      href: '/config',
      icon: <SlidersHorizontal size={20} />,
      label: isAr ? 'إعدادات النظام' : 'System Config',
      roles: ['system_admin'],
    },
  ];

  const visible = navItems.filter(
    (item) => user && item.roles.includes(user.role)
  );

  return (
    <>
      {/* Overlay mobile */}
      {open && (
        <div
          className="fixed inset-0 bg-black/40 z-20 lg:hidden"
          onClick={onClose}
        />
      )}

      <aside
        className={cn(
          'fixed top-0 h-full w-64 sidebar-bg text-white z-30 flex flex-col transition-transform duration-300',
          lang === 'ar' ? 'right-0' : 'left-0',
          open ? 'translate-x-0' : lang === 'ar' ? 'translate-x-full lg:translate-x-0' : '-translate-x-full lg:translate-x-0'
        )}
      >
        {/* Logo */}
        <div className="flex items-center justify-between px-4 py-5 border-b border-white/10">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 bg-blue-500 rounded-lg flex items-center justify-center font-bold text-lg">
              S
            </div>
            <div>
              <p className="text-sm font-bold leading-tight">SMMS</p>
              <p className="text-xs text-white/60">
                {lang === 'ar' ? 'إدارة الصيانة' : 'Maintenance Mgmt'}
              </p>
            </div>
          </div>
          <button onClick={onClose} className="lg:hidden text-white/60 hover:text-white">
            <X size={18} />
          </button>
        </div>

        {/* Nav */}
        <nav className="flex-1 overflow-y-auto py-4 px-2">
          {visible.map((item) => (
            <Link
              key={item.href}
              href={item.href}
              onClick={onClose}
              className={cn(
                'flex items-center gap-3 px-3 py-2.5 rounded-lg mb-1 text-sm transition-colors',
                pathname.startsWith(item.href)
                  ? 'sidebar-active text-white font-medium'
                  : 'text-white/70 hover:bg-white/10 hover:text-white'
              )}
            >
              {item.icon}
              <span>{item.label}</span>
            </Link>
          ))}
        </nav>

        {/* User info */}
        {user && (
          <div className="px-4 py-3 border-t border-white/10">
            <p className="text-xs font-semibold truncate">{user.fullName}</p>
            <p className="text-xs text-white/50 mt-0.5">
              {lang === 'ar' ? '@' : ''}{user.username}
            </p>
          </div>
        )}
      </aside>
    </>
  );
}
