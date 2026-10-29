'use client';

import { Button } from '@/components/ui/button';
import { useModuleT } from '@/lib/module-i18n';
import { workflowDict } from '@/modules/workflow/i18n';

/** Shown after a 409: the work order changed under the user. Reload fetches the current version. */
export function ConflictBanner({ onReload }: { onReload: () => void }) {
  const { t } = useModuleT(workflowDict);
  return (
    <div role="alert" className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900 dark:bg-amber-950/40 dark:text-amber-100">
      <div>
        <p className="font-semibold">{t('conflictTitle')}</p>
        <p>{t('conflictReload')}</p>
      </div>
      <Button size="sm" variant="outline" onClick={onReload}>{t('reload')}</Button>
    </div>
  );
}
