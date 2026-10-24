'use client';

import DashboardLayout from '@/components/layout/DashboardLayout';
import { PlanListView } from '@/modules/pm/components/PlanListView';

export default function MaintenancePlansPage() {
  return (
    <DashboardLayout>
      <div className="mx-auto max-w-6xl">
        <PlanListView />
      </div>
    </DashboardLayout>
  );
}
