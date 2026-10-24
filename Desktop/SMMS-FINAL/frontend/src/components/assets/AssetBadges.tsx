'use client';

import { Badge } from '@/components/ui/badge';
import { useModuleT } from '@/lib/module-i18n';
import { enumDict, enumLabel } from '@/modules/assets/enums';
import { assetsDict } from '@/modules/assets/i18n';
import type { Asset } from '@/modules/assets/types';

export function AssetStateBadges({ asset }: { asset: Pick<Asset, 'status' | 'criticality' | 'decommissionedAt' | 'activeDowntime'> }) {
  const { t } = useModuleT(assetsDict);
  const { t: te } = useModuleT(enumDict);
  return (
    <span className="inline-flex flex-wrap items-center gap-1">
      {asset.decommissionedAt ? (
        <Badge variant="outline">{t('decommissioned')}</Badge>
      ) : (
        <Badge variant={asset.status === 'broken' ? 'destructive' : asset.status === 'active' ? 'secondary' : 'default'}>
          {enumLabel(te, 'assetStatus', asset.status)}
        </Badge>
      )}
      {asset.criticality && (
        <Badge variant={asset.criticality === 'critical' || asset.criticality === 'high' ? 'destructive' : 'outline'}>
          {t('criticality')}: {enumLabel(te, 'crit', asset.criticality)}
        </Badge>
      )}
      {asset.activeDowntime && <Badge variant="destructive">{t('downtimeNow')}</Badge>}
    </span>
  );
}
