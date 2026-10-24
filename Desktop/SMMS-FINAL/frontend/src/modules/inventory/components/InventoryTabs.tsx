'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useModuleT } from '@/lib/module-i18n';
import { useAuthStore } from '@/store/authStore';
import { cn } from '@/lib/utils';
import { inventoryDict } from '../i18n';
import { can } from '../permissions';

/** In-page section links (the sidebar entries are owned by the coordinator). */
export function InventoryTabs() {
  const { t } = useModuleT(inventoryDict);
  const role = useAuthStore((s) => s.user?.role);
  const pathname = usePathname() ?? '';
  const tabs = [
    { href: '/inventory', label: t('itemsTitle'), show: true, exact: true },
    { href: '/inventory/warehouses', label: t('warehousesTitle'), show: can.readStock(role) },
    { href: '/inventory/movements', label: t('movementsTitle'), show: can.readMovements(role) },
    { href: '/inventory/low-stock', label: t('lowStockTitle'), show: can.readStock(role) },
    { href: '/inventory/receipts/new', label: t('receiptTitle'), show: can.receipt(role) },
    { href: '/inventory/adjustments/new', label: t('adjustmentTitle'), show: can.correct(role) },
  ].filter((x) => x.show);
  return (
    <nav aria-label={t('sections')} className="mx-auto mb-4 flex max-w-7xl flex-wrap gap-1 border-b pb-2">
      {tabs.map((x) => {
        const active = x.exact ? pathname === x.href : pathname.startsWith(x.href);
        return (
          <Link
            key={x.href}
            href={x.href}
            aria-current={active ? 'page' : undefined}
            className={cn('rounded-md px-3 py-1.5 text-sm hover:bg-muted max-md:py-2.5', active && 'bg-muted font-semibold')}
          >
            {x.label}
          </Link>
        );
      })}
    </nav>
  );
}
