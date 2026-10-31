'use client';

import DashboardLayout from '@/components/layout/DashboardLayout';
import { PermissionDenied } from '@/components/common';
import { useAuthStore } from '@/store/authStore';
import { useModuleT } from '@/lib/module-i18n';
import { workflowDict } from '@/modules/workflow/i18n';
import { DeliveriesTable, ProviderStatusCard } from '@/components/workflow/DeliveriesTable';

/** System admin view of the delivery log. The role check only hides the UI; the API is SA-only. */
export default function NotificationDeliveriesPage() {
  const { t } = useModuleT(workflowDict);
  const role = useAuthStore((s) => s.user?.role);
  return (
    <DashboardLayout>
      <div className="mx-auto max-w-6xl space-y-5">
        <div>
          <h1 className="text-2xl font-bold text-foreground">{t('deliveriesTitle')}</h1>
          <p className="text-sm text-muted-foreground">{t('deliveriesSubtitle')}</p>
        </div>
        {role === 'system_admin' ? (
          <>
            <ProviderStatusCard />
            <DeliveriesTable />
          </>
        ) : (
          <PermissionDenied description={t('adminOnly')} />
        )}
      </div>
    </DashboardLayout>
  );
}
