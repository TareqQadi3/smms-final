'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { PermissionDenied } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { useAuthStore } from '@/store/authStore';
import { hasRole, PM_WRITE } from '@/modules/assets/roles';
import { pmDict } from '../i18n';
import { PlanForm } from './PlanForm';

export function PlanCreateView() {
  const { t } = useModuleT(pmDict);
  const router = useRouter();
  const role = useAuthStore((s) => s.user?.role);
  if (!hasRole(role, PM_WRITE)) return <PermissionDenied />;
  return (
    <div className="mx-auto max-w-3xl space-y-5">
      <Link href="/maintenance-plans" className="text-sm text-primary underline-offset-4 hover:underline">{t('back')}</Link>
      <h1 className="text-2xl font-bold">{t('createTitle')}</h1>
      <PlanForm mode="create" onSaved={(p) => router.push(`/maintenance-plans/${p.id}`)} onCancel={() => router.push('/maintenance-plans')} />
    </div>
  );
}
