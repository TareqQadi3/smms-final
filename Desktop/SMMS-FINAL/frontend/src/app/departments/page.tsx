'use client';

import DashboardLayout from '@/components/layout/DashboardLayout';
import { DepartmentsView } from '@/modules/departments/components/DepartmentsView';

export default function DepartmentsPage() {
  return (
    <DashboardLayout>
      <div className="mx-auto max-w-6xl">
        <DepartmentsView />
      </div>
    </DashboardLayout>
  );
}
