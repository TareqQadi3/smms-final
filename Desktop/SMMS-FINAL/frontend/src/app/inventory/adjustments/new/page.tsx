'use client';

import DashboardLayout from '@/components/layout/DashboardLayout';
import { InventoryTabs } from '@/modules/inventory/components/InventoryTabs';
import { AdjustmentForm } from '@/modules/inventory/components/AdjustmentForm';

export default function Page() {
  return (
    <DashboardLayout>
      <InventoryTabs />
      <AdjustmentForm />
    </DashboardLayout>
  );
}
