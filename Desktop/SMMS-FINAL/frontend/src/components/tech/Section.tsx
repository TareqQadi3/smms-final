'use client';

import { useId, type ReactNode } from 'react';
import { cn } from '@/lib/utils';

/** Titled card used for each block of the ticket screen. */
export function Section({ title, children, className, aside }: { title: string; children: ReactNode; className?: string; aside?: ReactNode }) {
  const id = useId();
  return (
    <section aria-labelledby={id} className={cn('rounded-xl border border-border bg-card p-3.5 shadow-sm', className)}>
      <div className="mb-2.5 flex items-center justify-between gap-2">
        <h3 id={id} className="text-base font-semibold">{title}</h3>
        {aside}
      </div>
      {children}
    </section>
  );
}
