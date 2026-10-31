'use client';

import DashboardLayout from '@/components/layout/DashboardLayout';
import { PlanCreateView } from '@/modules/pm/components/PlanFormViews';

export default function NewMaintenancePlanPage() {
  return (
    <DashboardLayout>
      <PlanCreateView />
    </DashboardLayout>
  );
}
