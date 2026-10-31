'use client';

import DashboardLayout from '@/components/layout/DashboardLayout';
import { InventoryTabs } from '@/modules/inventory/components/InventoryTabs';
import { WarehousesPage } from '@/modules/inventory/components/WarehousesPage';

export default function Page() {
  return (
    <DashboardLayout>
      <InventoryTabs />
      <WarehousesPage />
    </DashboardLayout>
  );
}
