'use client';

import type { ReactNode } from 'react';
import { Inbox } from 'lucide-react';
import { useModuleT } from '@/lib/module-i18n';
import { cn } from '@/lib/utils';
import { commonDict } from './i18n';

/** Empty list/result state. `action` is an optional call-to-action node (e.g. a Button). */
export function EmptyState({
  title,
  description,
  action,
  className,
}: {
  title?: string;
  description?: ReactNode;
  action?: ReactNode;
  className?: string;
}) {
  const { t } = useModuleT(commonDict);
  return (
    <div role="status" className={cn('flex flex-col items-center justify-center gap-2 px-4 py-10 text-center', className)}>
      <Inbox size={24} aria-hidden="true" className="text-muted-foreground" />
      <p className="text-sm font-medium">{title ?? t('emptyTitle')}</p>
      {description && <p className="text-sm text-muted-foreground">{description}</p>}
      {action}
    </div>
  );
}
