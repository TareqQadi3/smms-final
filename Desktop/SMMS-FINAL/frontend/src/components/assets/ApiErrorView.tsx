'use client';

import { ErrorState, PermissionDenied } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { assetsDict } from '@/modules/assets/i18n';
import type { ApiErr } from '@/modules/assets/lib';

/** Load error: 403 becomes PermissionDenied, 404 a scope-safe not-found, 400/409/422 show the server message. */
export function ApiErrorView({ error, onRetry }: { error: ApiErr; onRetry?: () => void }) {
  const { t } = useModuleT(assetsDict);
  if (error.status === 403) return <PermissionDenied />;
  let message = t('loadFailed');
  if (error.status === 404) message = t('notFound');
  else if (error.message && (error.status === 400 || error.status === 409 || error.status === 422)) message = error.message;
  return <ErrorState message={message} onRetry={onRetry} />;
}
