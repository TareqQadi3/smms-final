'use client';

import DashboardLayout from '@/components/layout/DashboardLayout';
import { AssetDetailView } from '@/components/assets/AssetDetailView';

export default function AssetDetailPage({ params }: { params: { id: string } }) {
  return (
    <DashboardLayout>
      <div className="mx-auto max-w-6xl">
        <AssetDetailView id={params.id} />
      </div>
    </DashboardLayout>
  );
}
