'use client';

import DashboardLayout from '@/components/layout/DashboardLayout';
import { InventoryTabs } from '@/modules/inventory/components/InventoryTabs';
import { ItemsList } from '@/modules/inventory/components/ItemsList';

export default function Page() {
  return (
    <DashboardLayout>
      <InventoryTabs />
      <ItemsList />
    </DashboardLayout>
  );
}
