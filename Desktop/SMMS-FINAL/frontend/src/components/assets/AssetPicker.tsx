'use client';

import { useEffect, useId, useState, type KeyboardEvent } from 'react';
import { X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { useModuleT } from '@/lib/module-i18n';
import { assetsApi } from '@/modules/assets/api';
import { assetsDict } from '@/modules/assets/i18n';
import type { Asset, AssetOption } from '@/modules/assets/types';

export type { AssetOption };

interface Props {
  /** Selected asset (controlled). Decommissioned assets are never offered. */
  value: AssetOption | null;
  onChange: (asset: AssetOption | null) => void;
  /** Limit the search to one center (e.g. the center of the work order being created). */
  centerId?: string;
  label?: string;
  id?: string;
  required?: boolean;
  disabled?: boolean;
  error?: string;
  hint?: string;
  /** Search debounce in ms (tests pass 0). */
  debounceMs?: number;
}

/**
 * Searchable asset combobox for forms (new request, maintenance plan, work order asset link).
 * Keyboard: type to search, ArrowUp/ArrowDown to move, Enter to pick, Escape to close.
 */
export function AssetPicker({ value, onChange, centerId, label, id, required, disabled, error, hint, debounceMs = 300 }: Props) {
  const { t, lang } = useModuleT(assetsDict);
  const auto = useId();
  const inputId = id ?? `asset-picker-${auto}`;
  const listId = `${inputId}-list`;
  const errId = `${inputId}-error`;
  const hintId = `${inputId}-hint`;
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const [results, setResults] = useState<Asset[]>([]);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);
  const [active, setActive] = useState(-1);

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    const run = () => {
      setLoading(true);
      setFailed(false);
      assetsApi
        .list({ q: query.trim(), centerId, size: 20, includeDecommissioned: false })
        .then((p) => {
          if (cancelled) return;
          setResults(p.content.filter((a) => !a.decommissionedAt));
          setActive(-1);
        })
        .catch(() => {
          if (!cancelled) setFailed(true);
        })
        .finally(() => {
          if (!cancelled) setLoading(false);
        });
    };
    const timer = setTimeout(run, debounceMs);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [open, query, centerId, debounceMs]);

  const display = (a: AssetOption) => {
    const n = lang === 'ar' ? a.nameAr || a.name : a.name || a.nameAr;
    return a.assetCode ? `${a.assetCode} - ${n}` : n;
  };

  const pick = (a: Asset) => {
    onChange({ id: a.id, assetCode: a.assetCode, name: a.name, nameAr: a.nameAr, center: a.center });
    setOpen(false);
    setQuery('');
  };

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setOpen(true);
      setActive((i) => Math.min(i + 1, results.length - 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive((i) => Math.max(i - 1, 0));
    } else if (e.key === 'Enter' && open && active >= 0 && results[active]) {
      e.preventDefault();
      pick(results[active]);
    } else if (e.key === 'Escape') {
      setOpen(false);
    }
  };

  const describedBy = [error ? errId : '', hint ? hintId : ''].filter(Boolean).join(' ') || undefined;

  return (
    <div className="space-y-1.5">
      <Label htmlFor={inputId}>
        {label ?? t('pickerLabel')}
        {required && (
          <>
            <span aria-hidden="true" className="text-destructive">*</span>
            <span className="sr-only">({t('required')})</span>
          </>
        )}
      </Label>
      {value && (
        <div className="flex items-center gap-2 rounded-lg border bg-muted/40 px-3 py-1.5 text-sm" data-testid="asset-picker-selected">
          <span className="flex-1 min-w-0 truncate">{t('pickerSelected', { name: display(value) })}</span>
          <Button type="button" variant="ghost" size="icon-sm" aria-label={t('pickerClear')} disabled={disabled} onClick={() => onChange(null)}>
            <X aria-hidden="true" />
          </Button>
        </div>
      )}
      <div className="relative">
        <Input
          id={inputId}
          type="search"
          role="combobox"
          aria-expanded={open}
          aria-controls={listId}
          aria-autocomplete="list"
          aria-activedescendant={active >= 0 ? `${listId}-${active}` : undefined}
          aria-invalid={error ? true : undefined}
          aria-required={required || undefined}
          aria-describedby={describedBy}
          autoComplete="off"
          disabled={disabled}
          placeholder={t('pickerPlaceholder')}
          value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            setOpen(true);
          }}
          onFocus={() => setOpen(true)}
          onBlur={() => setTimeout(() => setOpen(false), 150)}
          onKeyDown={onKeyDown}
        />
        {open && (
          <ul
            id={listId}
            role="listbox"
            aria-label={label ?? t('pickerLabel')}
            className="absolute inset-x-0 z-20 mt-1 max-h-60 overflow-auto rounded-lg border bg-popover text-popover-foreground shadow-md"
          >
            {loading && <li role="presentation" className="px-3 py-2 text-sm text-muted-foreground">{t('pickerSearching')}</li>}
            {failed && <li role="presentation" className="px-3 py-2 text-sm text-destructive">{t('pickerError')}</li>}
            {!loading && !failed && results.length === 0 && (
              <li role="presentation" className="px-3 py-2 text-sm text-muted-foreground">{t('pickerNone')}</li>
            )}
            {!loading &&
              results.map((a, i) => (
                <li
                  key={a.id}
                  id={`${listId}-${i}`}
                  role="option"
                  aria-selected={i === active}
                  className={`cursor-pointer px-3 py-2 text-sm min-h-10 ${i === active ? 'bg-muted' : 'hover:bg-muted/60'}`}
                  onMouseDown={(e) => e.preventDefault()}
                  onClick={() => pick(a)}
                >
                  {display(a)}
                  <span className="ms-2 text-xs text-muted-foreground">{lang === 'ar' ? a.center?.nameAr : a.center?.name}</span>
                </li>
              ))}
          </ul>
        )}
      </div>
      <p className="sr-only" role="status" aria-live="polite">
        {open && !loading && !failed ? t('pickerResults', { n: results.length }) : ''}
      </p>
      {hint && <p id={hintId} className="text-xs text-muted-foreground">{hint}</p>}
      {error && <p id={errId} role="alert" className="text-xs text-destructive">{error}</p>}
    </div>
  );
}

export default AssetPicker;
