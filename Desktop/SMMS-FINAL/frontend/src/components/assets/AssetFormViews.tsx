'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useCallback } from 'react';
import { LoadingState, PermissionDenied } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { useAuthStore } from '@/store/authStore';
import { assetsApi } from '@/modules/assets/api';
import { assetsDict } from '@/modules/assets/i18n';
import { useLoad } from '@/modules/assets/lib';
import { ASSET_WRITE, hasRole } from '@/modules/assets/roles';
import { ApiErrorView } from './ApiErrorView';
import { AssetForm } from './AssetForm';

export function AssetCreateView() {
  const { t } = useModuleT(assetsDict);
  const router = useRouter();
  const role = useAuthStore((s) => s.user?.role);
  if (!hasRole(role, ASSET_WRITE)) return <PermissionDenied />;
  return (
    <div className="mx-auto max-w-4xl space-y-5">
      <Link href="/assets" className="text-sm text-primary underline-offset-4 hover:underline">{t('back')}</Link>
      <h1 className="text-2xl font-bold">{t('createTitle')}</h1>
      <AssetForm mode="create" onSaved={(a) => router.push(`/assets/${a.id}`)} onCancel={() => router.push('/assets')} />
    </div>
  );
}

export function AssetEditView({ id }: { id: string }) {
  const { t } = useModuleT(assetsDict);
  const router = useRouter();
  const role = useAuthStore((s) => s.user?.role);
  const allowed = hasRole(role, ASSET_WRITE);
  const fetcher = useCallback(() => assetsApi.get(id), [id]);
  const { data, error, loading, reload } = useLoad(fetcher, allowed);
  if (!allowed) return <PermissionDenied />;
  if (loading) return <LoadingState />;
  if (error) return <ApiErrorView error={error} onRetry={reload} />;
  if (!data) return null;
  return (
    <div className="mx-auto max-w-4xl space-y-5">
      <Link href={`/assets/${id}`} className="text-sm text-primary underline-offset-4 hover:underline">{t('back')}</Link>
      <h1 className="text-2xl font-bold">{t('editTitle')}</h1>
      {data.decommissionedAt ? (
        <p role="alert" className="rounded-lg border p-3 text-sm">{t('decommissionedReadOnly')}</p>
      ) : (
        <AssetForm mode="edit" asset={data} onSaved={() => router.push(`/assets/${id}`)} onCancel={() => router.push(`/assets/${id}`)} />
      )}
    </div>
  );
}
