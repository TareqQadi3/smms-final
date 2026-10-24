'use client';

import DashboardLayout from '@/components/layout/DashboardLayout';
import { ReportsView } from '@/modules/reports/components/ReportsView';

export default function ReportsPage() {
  return (
    <DashboardLayout>
      <div className="mx-auto max-w-6xl">
        <ReportsView />
      </div>
    </DashboardLayout>
  );
}
