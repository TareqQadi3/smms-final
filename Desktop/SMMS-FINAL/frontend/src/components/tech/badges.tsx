'use client';

import { priorityColors, statusColors, tPriority, tStatus } from '@/lib/i18n';
import { useAuthStore } from '@/store/authStore';
import { cn } from '@/lib/utils';

export function StatusBadge({ status, className }: { status: string; className?: string }) {
  const lang = useAuthStore((s) => s.lang);
  return (
    <span className={cn('inline-flex items-center rounded-full px-2.5 py-1 text-xs font-semibold', statusColors[status] ?? 'bg-gray-100 text-gray-800', className)}>
      {tStatus(status, lang)}
    </span>
  );
}

export function PriorityBadge({ priority, className }: { priority: string; className?: string }) {
  const lang = useAuthStore((s) => s.lang);
  return (
    <span className={cn('inline-flex items-center rounded-md border px-2 py-0.5 text-xs font-semibold', priorityColors[priority] ?? '', className)}>
      {tPriority(priority, lang)}
    </span>
  );
}
