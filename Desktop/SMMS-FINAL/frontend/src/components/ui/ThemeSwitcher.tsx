'use client';

import { useThemeStore, ThemeColor } from '@/store/themeStore';
import { Sun, Moon } from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { cn } from '@/lib/utils';

const COLORS: { value: ThemeColor; label: string; labelEn: string; dot: string }[] = [
  { value: 'blue',  label: 'أزرق', labelEn: 'Blue',  dot: 'bg-blue-500'  },
  { value: 'green', label: 'أخضر', labelEn: 'Green', dot: 'bg-green-500' },
];

export default function ThemeSwitcher() {
  const { color, mode, setColor, toggle } = useThemeStore();
  const isAr = useAuthStore((s) => s.lang) === 'ar';

  return (
    <div className="flex items-center gap-1 sm:gap-2">
      {/* Color swatches */}
      <div role="group" aria-label={isAr ? 'لون السمة' : 'Theme color'} className="flex items-center border border-border rounded-full px-1">
        {COLORS.map((c) => {
          const name = isAr ? c.label : c.labelEn;
          return (
            <button
              key={c.value}
              type="button"
              onClick={() => setColor(c.value)}
              title={name}
              aria-label={name}
              aria-pressed={color === c.value}
              className="flex size-10 md:size-8 items-center justify-center rounded-full outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              <span
                className={cn(
                  'block w-4 h-4 rounded-full transition-all ring-offset-background',
                  c.dot,
                  color === c.value
                    ? 'ring-2 ring-offset-1 ring-primary scale-110'
                    : 'opacity-50'
                )}
              />
            </button>
          );
        })}
      </div>

      {/* Dark / Light toggle */}
      <button
        type="button"
        onClick={toggle}
        title={mode === 'dark' ? (isAr ? 'وضع فاتح' : 'Light mode') : (isAr ? 'وضع غامق' : 'Dark mode')}
        aria-label={mode === 'dark' ? (isAr ? 'وضع فاتح' : 'Light mode') : (isAr ? 'وضع غامق' : 'Dark mode')}
        className={cn(
          'size-10 md:size-8 rounded-full outline-none focus-visible:ring-2 focus-visible:ring-ring flex items-center justify-center transition-colors',
          'border border-border hover:bg-accent text-foreground'
        )}
      >
        {mode === 'dark' ? <Sun size={15} aria-hidden="true" /> : <Moon size={15} aria-hidden="true" />}
      </button>
    </div>
  );
}
