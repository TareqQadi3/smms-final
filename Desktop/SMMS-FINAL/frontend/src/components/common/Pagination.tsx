'use client';

import { ChevronLeft, ChevronRight } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useModuleT } from '@/lib/module-i18n';
import { cn } from '@/lib/utils';
import { commonDict } from './i18n';

/**
 * Zero-based page control matching Spring `Page` (`number`, `totalPages`). Renders nothing when
 * there is a single page. Chevrons mirror automatically in RTL.
 */
export function Pagination({
  page,
  totalPages,
  onPageChange,
  disabled,
  className,
}: {
  page: number;
  totalPages: number;
  onPageChange: (page: number) => void;
  disabled?: boolean;
  className?: string;
}) {
  const { t, isAr } = useModuleT(commonDict);
  if (totalPages <= 1) return null;
  const Prev = isAr ? ChevronRight : ChevronLeft;
  const Next = isAr ? ChevronLeft : ChevronRight;
  return (
    <nav aria-label={t('pagination')} className={cn('flex items-center justify-between gap-2 py-2', className)}>
      <Button variant="outline" size="sm" disabled={disabled || page <= 0} onClick={() => onPageChange(page - 1)}>
        <Prev size={14} aria-hidden="true" />
        {t('previous')}
      </Button>
      <span aria-live="polite" className="text-sm text-muted-foreground">
        {t('pageOf', { page: page + 1, total: totalPages })}
      </span>
      <Button
        variant="outline"
        size="sm"
        disabled={disabled || page >= totalPages - 1}
        onClick={() => onPageChange(page + 1)}
      >
        {t('next')}
        <Next size={14} aria-hidden="true" />
      </Button>
    </nav>
  );
}
