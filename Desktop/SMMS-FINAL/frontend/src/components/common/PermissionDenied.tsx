'use client';

import type { ReactNode } from 'react';
import { ShieldAlert } from 'lucide-react';
import { useModuleT } from '@/lib/module-i18n';
import { cn } from '@/lib/utils';
import { commonDict } from './i18n';

/** Shown instead of a page/section when the role lacks access (client-side hint only; the server enforces). */
export function PermissionDenied({
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
    <div role="alert" className={cn('flex flex-col items-center justify-center gap-2 px-4 py-10 text-center', className)}>
      <ShieldAlert size={24} aria-hidden="true" className="text-muted-foreground" />
      <p className="text-sm font-medium">{title ?? t('deniedTitle')}</p>
      <p className="text-sm text-muted-foreground">{description ?? t('deniedBody')}</p>
      {action}
    </div>
  );
}
