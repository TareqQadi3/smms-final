'use client';

import { AlertCircle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useModuleT } from '@/lib/module-i18n';
import { cn } from '@/lib/utils';
import { commonDict } from './i18n';

/** Error block (role=alert) with optional retry. Pass an already-localized `message`. */
export function ErrorState({
  title,
  message,
  onRetry,
  className,
}: {
  title?: string;
  message?: string;
  onRetry?: () => void;
  className?: string;
}) {
  const { t } = useModuleT(commonDict);
  return (
    <div role="alert" className={cn('flex flex-col items-center justify-center gap-2 px-4 py-10 text-center', className)}>
      <AlertCircle size={24} aria-hidden="true" className="text-destructive" />
      <p className="text-sm font-medium text-destructive">{title ?? t('errorTitle')}</p>
      <p className="text-sm text-muted-foreground">{message ?? t('errorBody')}</p>
      {onRetry && (
        <Button variant="outline" size="sm" onClick={onRetry}>
          {t('retry')}
        </Button>
      )}
    </div>
  );
}
