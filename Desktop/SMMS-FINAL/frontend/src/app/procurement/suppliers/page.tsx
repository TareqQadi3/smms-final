'use client';

import DashboardLayout from '@/components/layout/DashboardLayout';
import { SuppliersPage } from '@/modules/procurement/components/SuppliersPage';

export default function Page() {
  return (
    <DashboardLayout>
      <SuppliersPage />
    </DashboardLayout>
  );
}
