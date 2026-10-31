'use client';

import DashboardLayout from '@/components/layout/DashboardLayout';
import { MaterialRequestDetail } from '@/modules/materials/components/MaterialRequestDetail';

export default function Page({ params }: { params: { id: string } }) {
  return (
    <DashboardLayout>
      <MaterialRequestDetail id={params.id} />
    </DashboardLayout>
  );
}
