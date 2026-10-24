'use client';

import DashboardLayout from '@/components/layout/DashboardLayout';
import { InventoryTabs } from '@/modules/inventory/components/InventoryTabs';
import { ReceiptForm } from '@/modules/inventory/components/ReceiptForm';

export default function Page() {
  return (
    <DashboardLayout>
      <InventoryTabs />
      <ReceiptForm />
    </DashboardLayout>
  );
}
