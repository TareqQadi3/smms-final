'use client';

import DashboardLayout from '@/components/layout/DashboardLayout';
import { PurchaseOrderForm } from '@/modules/procurement/components/PurchaseOrderForm';

export default function Page({ params }: { params: { id: string } }) {
  return (
    <DashboardLayout>
      <PurchaseOrderForm poId={params.id} />
    </DashboardLayout>
  );
}
