'use client';

import { useRef } from 'react';
import { Star } from 'lucide-react';

/**
 * 1-5 stars as a radio group (arrow keys move, Home/End jump), 44 px targets. `readOnly` renders a
 * static display with an accessible text value.
 */
export function StarRating({
  value, onChange, label, starLabel, readOnly, disabled,
}: {
  value: number;
  onChange?: (n: number) => void;
  label: string;
  starLabel: (n: number) => string;
  readOnly?: boolean;
  disabled?: boolean;
}) {
  const refs = useRef<(HTMLButtonElement | null)[]>([]);

  if (readOnly) {
    return (
      <div role="img" aria-label={starLabel(value)} className="flex gap-0.5" dir="ltr">
        {[1, 2, 3, 4, 5].map((n) => (
          <Star key={n} size={22} aria-hidden="true" className={n <= value ? 'fill-amber-400 text-amber-500' : 'text-muted-foreground/40'} />
        ))}
      </div>
    );
  }

  const select = (n: number) => {
    const next = Math.min(5, Math.max(1, n));
    onChange?.(next);
    refs.current[next - 1]?.focus();
  };

  return (
    <div role="radiogroup" aria-label={label} className="flex gap-1" dir="ltr">
      {[1, 2, 3, 4, 5].map((n) => (
        <button
          key={n}
          ref={(el) => { refs.current[n - 1] = el; }}
          type="button"
          role="radio"
          aria-checked={value === n}
          aria-label={starLabel(n)}
          disabled={disabled}
          // roving tabindex: only the checked star (or the first) is in the tab order
          tabIndex={value === n || (value === 0 && n === 1) ? 0 : -1}
          onClick={() => onChange?.(n)}
          onKeyDown={(e) => {
            if (e.key === 'ArrowRight' || e.key === 'ArrowUp') { e.preventDefault(); select((value || 0) + 1); }
            else if (e.key === 'ArrowLeft' || e.key === 'ArrowDown') { e.preventDefault(); select((value || 2) - 1); }
            else if (e.key === 'Home') { e.preventDefault(); select(1); }
            else if (e.key === 'End') { e.preventDefault(); select(5); }
          }}
          className="inline-flex size-11 items-center justify-center rounded-lg outline-none focus-visible:ring-3 focus-visible:ring-ring/50 disabled:opacity-50"
        >
          <Star size={28} aria-hidden="true" className={n <= value ? 'fill-amber-400 text-amber-500' : 'text-muted-foreground/50'} />
        </button>
      ))}
    </div>
  );
}
