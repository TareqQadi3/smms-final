'use client';

import { Skeleton } from '@/components/ui/skeleton';
import { useModuleT } from '@/lib/module-i18n';
import { cn } from '@/lib/utils';
import { commonDict } from './i18n';

/** Accessible loading placeholder (role=status, aria-busy) with skeleton rows. */
export function LoadingState({ rows = 3, label, className }: { rows?: number; label?: string; className?: string }) {
  const { t } = useModuleT(commonDict);
  return (
    <div role="status" aria-busy="true" aria-live="polite" className={cn('space-y-2 p-4', className)}>
      <span className="sr-only">{label ?? t('loading')}</span>
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} className="h-8 w-full" aria-hidden="true" />
      ))}
    </div>
  );
}
