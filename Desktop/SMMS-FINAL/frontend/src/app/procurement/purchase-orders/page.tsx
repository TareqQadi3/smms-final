'use client';

import DashboardLayout from '@/components/layout/DashboardLayout';
import { PurchaseOrdersList } from '@/modules/procurement/components/PurchaseOrdersList';

export default function Page() {
  return (
    <DashboardLayout>
      <PurchaseOrdersList />
    </DashboardLayout>
  );
}
