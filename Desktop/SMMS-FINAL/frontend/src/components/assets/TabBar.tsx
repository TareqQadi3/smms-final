'use client';

import { useRef, type KeyboardEvent } from 'react';
import { cn } from '@/lib/utils';

export interface TabItem {
  id: string;
  label: string;
}

/** Accessible tab list (roving tabindex, arrow keys, Home/End). The caller renders the role="tabpanel". */
export function TabBar({
  tabs,
  active,
  onChange,
  idPrefix,
  label,
}: {
  tabs: TabItem[];
  active: string;
  onChange: (id: string) => void;
  idPrefix: string;
  label: string;
}) {
  const refs = useRef<Record<string, HTMLButtonElement | null>>({});

  const onKeyDown = (e: KeyboardEvent<HTMLButtonElement>, index: number) => {
    // Arrow direction follows the document direction: ArrowRight is "next" in LTR, "previous" in RTL.
    const rtl = typeof document !== 'undefined' && document.documentElement.dir === 'rtl';
    let next = index;
    if (e.key === 'ArrowRight') next = index + (rtl ? -1 : 1);
    else if (e.key === 'ArrowLeft') next = index + (rtl ? 1 : -1);
    else if (e.key === 'Home') next = 0;
    else if (e.key === 'End') next = tabs.length - 1;
    else return;
    e.preventDefault();
    next = (next + tabs.length) % tabs.length;
    const id = tabs[next].id;
    onChange(id);
    refs.current[id]?.focus();
  };

  return (
    <div role="tablist" aria-label={label} className="flex flex-wrap gap-1 border-b">
      {tabs.map((tab, i) => {
        const selected = tab.id === active;
        return (
          <button
            key={tab.id}
            ref={(el) => {
              refs.current[tab.id] = el;
            }}
            type="button"
            role="tab"
            id={`${idPrefix}-tab-${tab.id}`}
            aria-selected={selected}
            aria-controls={`${idPrefix}-panel-${tab.id}`}
            tabIndex={selected ? 0 : -1}
            onClick={() => onChange(tab.id)}
            onKeyDown={(e) => onKeyDown(e, i)}
            className={cn(
              'min-h-10 px-4 py-2 text-sm font-medium border-b-2 -mb-px outline-none focus-visible:ring-2 focus-visible:ring-ring',
              selected ? 'border-primary text-foreground' : 'border-transparent text-muted-foreground hover:text-foreground'
            )}
          >
            {tab.label}
          </button>
        );
      })}
    </div>
  );
}
