'use client';

import { Badge } from '@/components/ui/badge';
import { useModuleT } from '@/lib/module-i18n';
import { materialsDict } from '../i18n';
import type { MrStatus } from '../types';

const variant: Record<MrStatus, 'default' | 'secondary' | 'destructive' | 'outline'> = {
  submitted: 'secondary',
  approved: 'default',
  rejected: 'destructive',
  reserved: 'default',
  issued: 'default',
  closed: 'outline',
  cancelled: 'destructive',
};

export function MrStatusBadge({ status }: { status: MrStatus }) {
  const { t } = useModuleT(materialsDict);
  return <Badge variant={variant[status] ?? 'outline'}>{t(`status_${status}`)}</Badge>;
}
