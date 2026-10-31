'use client';

import { Suspense } from 'react';
import DashboardLayout from '@/components/layout/DashboardLayout';
import { InventoryTabs } from '@/modules/inventory/components/InventoryTabs';
import { MovementsPage } from '@/modules/inventory/components/MovementsPage';

export default function Page() {
  return (
    <DashboardLayout>
      <InventoryTabs />
      <Suspense fallback={null}>
        <MovementsPage />
      </Suspense>
    </DashboardLayout>
  );
}
