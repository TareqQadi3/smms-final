'use client';

import { useThemeStore, ThemeColor } from '@/store/themeStore';
import { Sun, Moon } from 'lucide-react';
import { cn } from '@/lib/utils';

const COLORS: { value: ThemeColor; label: string; dot: string }[] = [
  { value: 'blue',  label: 'أزرق',  dot: 'bg-blue-500'  },
  { value: 'green', label: 'أخضر', dot: 'bg-green-500' },
];

export default function ThemeSwitcher() {
  const { color, mode, setColor, toggle } = useThemeStore();

  return (
    <div className="flex items-center gap-2">
      {/* Color swatches */}
      <div className="flex items-center gap-1.5 border border-border rounded-full px-2 py-1">
        {COLORS.map((c) => (
          <button
            key={c.value}
            onClick={() => setColor(c.value)}
            title={c.label}
            className={cn(
              'w-4 h-4 rounded-full transition-all ring-offset-background',
              c.dot,
              color === c.value
                ? 'ring-2 ring-offset-1 ring-primary scale-110'
                : 'opacity-50 hover:opacity-80'
            )}
          />
        ))}
      </div>

      {/* Dark / Light toggle */}
      <button
        onClick={toggle}
        title={mode === 'dark' ? 'وضع فاتح' : 'وضع غامق'}
        className={cn(
          'w-8 h-8 rounded-full flex items-center justify-center transition-colors',
          'border border-border hover:bg-accent text-foreground'
        )}
      >
        {mode === 'dark' ? <Sun size={15} /> : <Moon size={15} />}
      </button>
    </div>
  );
}
