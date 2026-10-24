'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';

/** The technician work area moved to the mobile-first /tech queue; keep old links and bookmarks working. */
export default function MyTasksRedirect() {
  const router = useRouter();
  useEffect(() => { router.replace('/tech'); }, [router]);
  return null;
}
