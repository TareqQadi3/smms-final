'use client';

import DashboardLayout from '@/components/layout/DashboardLayout';
import { PurchaseOrderForm } from '@/modules/procurement/components/PurchaseOrderForm';

export default function Page() {
  return (
    <DashboardLayout>
      <PurchaseOrderForm />
    </DashboardLayout>
  );
}
