'use client';

import DashboardLayout from '@/components/layout/DashboardLayout';
import { AssetCreateView } from '@/components/assets/AssetFormViews';

export default function NewAssetPage() {
  return (
    <DashboardLayout>
      <AssetCreateView />
    </DashboardLayout>
  );
}
