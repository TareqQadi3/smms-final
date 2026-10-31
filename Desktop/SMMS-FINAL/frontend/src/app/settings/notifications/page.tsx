'use client';

import DashboardLayout from '@/components/layout/DashboardLayout';
import { useModuleT } from '@/lib/module-i18n';
import { workflowDict } from '@/modules/workflow/i18n';
import { NotificationPreferencesForm } from '@/components/workflow/NotificationPreferencesForm';

export default function NotificationSettingsPage() {
  const { t } = useModuleT(workflowDict);
  return (
    <DashboardLayout>
      <div className="mx-auto max-w-2xl space-y-5">
        <h1 className="text-2xl font-bold text-foreground">{t('prefsTitle')}</h1>
        <NotificationPreferencesForm />
      </div>
    </DashboardLayout>
  );
}
