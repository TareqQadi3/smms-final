'use client';

import DashboardLayout from '@/components/layout/DashboardLayout';
import { MaterialRequestsList } from '@/modules/materials/components/MaterialRequestsList';

export default function Page() {
  return (
    <DashboardLayout>
      <MaterialRequestsList />
    </DashboardLayout>
  );
}
