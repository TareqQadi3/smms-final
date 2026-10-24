import type { Metadata } from 'next';
import TechShell from '@/components/tech/TechShell';

export const metadata: Metadata = { title: 'SMMS - مهامي الميدانية' };

export default function TechLayout({ children }: { children: React.ReactNode }) {
  return <TechShell>{children}</TechShell>;
}
