'use client';

import { useModuleT } from '@/lib/module-i18n';
import { techDict } from '@/modules/tech/i18n';
import { cn } from '@/lib/utils';
import type { RunResult } from './useActionRunner';

/** Announces the outcome of an action (role=status for ok/queued, role=alert for errors). */
export function ActionResult({ result, className }: { result: RunResult | null; className?: string }) {
  const { t } = useModuleT(techDict);
  if (!result) return null;
  if (result.kind === 'error') {
    return (
      <div role="alert" className={cn('rounded-lg border border-red-300 bg-red-50 px-3 py-2 text-sm text-red-900', className)}>
        <p className="font-medium">{result.error.text}</p>
        {result.error.detail && (
          <p className="mt-0.5 text-xs"><bdi dir="auto">{t('errServerSays', { message: result.error.detail })}</bdi></p>
        )}
      </div>
    );
  }
  return (
    <p
      role="status"
      className={cn(
        'rounded-lg border px-3 py-2 text-sm',
        result.kind === 'ok' ? 'border-green-300 bg-green-50 text-green-900' : 'border-blue-300 bg-blue-50 text-blue-900',
        className
      )}
    >
      {result.text}
    </p>
  );
}
