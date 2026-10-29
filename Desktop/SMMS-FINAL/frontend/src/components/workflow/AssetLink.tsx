'use client';

import Link from 'next/link';
import { Wrench } from 'lucide-react';
import { useModuleT } from '@/lib/module-i18n';
import { workflowDict } from '@/modules/workflow/i18n';
import type { WorkOrderDetail } from '@/modules/workflow/types';

/** Asset of the work order with a link to its record (the assets screen is owned by another module). */
export function AssetLink({ wo }: { wo: WorkOrderDetail }) {
  const { t, isAr } = useModuleT(workflowDict);
  const a = wo.asset;
  if (!a && !wo.assetId) return null;
  const name = a ? (isAr && a.nameAr) || a.name : wo.assetId;
  return (
    <div className="text-sm">
      <p className="text-xs text-muted-foreground">{t('asset')}</p>
      <Link href={`/assets/${a?.id ?? wo.assetId}`} className="inline-flex items-center gap-1.5 font-medium text-primary underline-offset-2 hover:underline" aria-label={`${t('assetLink')}: ${name}`}>
        <Wrench size={14} aria-hidden />
        {a?.assetCode && <bdi dir="ltr" className="font-mono text-xs">{a.assetCode}</bdi>}
        <span>{name}</span>
      </Link>
    </div>
  );
}
