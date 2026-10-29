import type { Metadata } from 'next';

// Public page: never indexed, never leaks the token through the Referer header
export const metadata: Metadata = {
  title: 'SMMS - Request status',
  robots: { index: false, follow: false },
  referrer: 'no-referrer',
};

export default function StatusLayout({ children }: { children: React.ReactNode }) {
  return children;
}
