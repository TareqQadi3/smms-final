'use client';

import DashboardLayout from '@/components/layout/DashboardLayout';
import { AssetListView } from '@/components/assets/AssetListView';

export default function AssetsPage() {
  return (
    <DashboardLayout>
      <div className="mx-auto max-w-6xl">
        <AssetListView />
      </div>
    </DashboardLayout>
  );
}
