'use client';

import { Badge } from '@/components/ui/badge';
import { useModuleT } from '@/lib/module-i18n';
import { procurementDict } from '../i18n';
import type { PoStatus } from '../types';

const variant: Record<PoStatus, 'default' | 'secondary' | 'destructive' | 'outline'> = {
  draft: 'outline',
  submitted: 'secondary',
  approved: 'default',
  rejected: 'destructive',
  partially_received: 'secondary',
  received: 'default',
  cancelled: 'destructive',
  closed: 'outline',
};

export function PoStatusBadge({ status }: { status: PoStatus }) {
  const { t } = useModuleT(procurementDict);
  return <Badge variant={variant[status] ?? 'outline'}>{t(`status_${status}`)}</Badge>;
}
