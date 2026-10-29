'use client';

import DashboardLayout from '@/components/layout/DashboardLayout';
import { PlanDetailView } from '@/modules/pm/components/PlanDetailView';

export default function MaintenancePlanDetailPage({ params }: { params: { id: string } }) {
  return (
    <DashboardLayout>
      <div className="mx-auto max-w-5xl">
        <PlanDetailView id={params.id} />
      </div>
    </DashboardLayout>
  );
}
