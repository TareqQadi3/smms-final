'use client';

import DashboardLayout from '@/components/layout/DashboardLayout';
import { AssetEditView } from '@/components/assets/AssetFormViews';

export default function EditAssetPage({ params }: { params: { id: string } }) {
  return (
    <DashboardLayout>
      <AssetEditView id={params.id} />
    </DashboardLayout>
  );
}
