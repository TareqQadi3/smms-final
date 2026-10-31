'use client';

import DashboardLayout from '@/components/layout/DashboardLayout';
import { InventoryTabs } from '@/modules/inventory/components/InventoryTabs';
import { LowStockPage } from '@/modules/inventory/components/LowStockPage';

export default function Page() {
  return (
    <DashboardLayout>
      <InventoryTabs />
      <LowStockPage />
    </DashboardLayout>
  );
}
