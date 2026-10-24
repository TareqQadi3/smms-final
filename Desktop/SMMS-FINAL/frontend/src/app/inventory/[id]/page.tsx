'use client';

import DashboardLayout from '@/components/layout/DashboardLayout';
import { InventoryTabs } from '@/modules/inventory/components/InventoryTabs';
import { ItemDetail } from '@/modules/inventory/components/ItemDetail';

export default function Page({ params }: { params: { id: string } }) {
  return (
    <DashboardLayout>
      <InventoryTabs />
      <ItemDetail id={params.id} />
    </DashboardLayout>
  );
}
