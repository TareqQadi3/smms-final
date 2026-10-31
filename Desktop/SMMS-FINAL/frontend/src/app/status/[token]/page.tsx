'use client';

import { useParams } from 'next/navigation';
import { PublicStatusView } from '@/components/requests/PublicStatusView';

export default function PublicStatusPage() {
  const params = useParams<{ token: string }>();
  const token = Array.isArray(params.token) ? params.token[0] : params.token;
  return <PublicStatusView token={token} />;
}
