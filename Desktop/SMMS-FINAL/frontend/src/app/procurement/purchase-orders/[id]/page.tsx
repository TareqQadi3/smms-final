'use client';

import DashboardLayout from '@/components/layout/DashboardLayout';
import { PurchaseOrderDetail } from '@/modules/procurement/components/PurchaseOrderDetail';

export default function Page({ params }: { params: { id: string } }) {
  return (
    <DashboardLayout>
      <PurchaseOrderDetail id={params.id} />
    </DashboardLayout>
  );
}
