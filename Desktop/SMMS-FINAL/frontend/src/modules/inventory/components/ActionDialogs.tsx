'use client';

import { useEffect, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { FormField } from '@/components/common';
import { useModuleT, type ModuleDict } from '@/lib/module-i18n';
import { useAuthStore } from '@/store/authStore';
import { mapApiError } from '../errors';
import { Banner, parseQty } from '../shared';

const ar = {
  cancel: 'إلغاء',
  working: 'جارٍ التنفيذ...',
  required: 'هذا الحقل مطلوب',
  minChars: 'الحد الأدنى {n} أحرف',
  qtyInvalid: 'أدخل كمية صحيحة أكبر من صفر (3 خانات عشرية كحد أقصى)',
  qtyDecimals: 'الحد الأقصى 3 خانات عشرية',
  qtyMax: 'الكمية تتجاوز الحد المسموح ({max})',
  atLeastOne: 'أدخل كمية لبند واحد على الأقل',
  quantity: 'الكمية',
  upTo: 'الحد الأقصى {max}',
};
const en: Partial<typeof ar> = {
  cancel: 'Cancel',
  working: 'Working...',
  required: 'This field is required',
  minChars: 'At least {n} characters',
  qtyInvalid: 'Enter a valid quantity greater than zero (max 3 decimals)',
  qtyDecimals: 'At most 3 decimals',
  qtyMax: 'Quantity exceeds the allowed maximum ({max})',
  atLeastOne: 'Enter a quantity for at least one line',
  quantity: 'Quantity',
  upTo: 'Up to {max}',
};
const dict: ModuleDict<keyof typeof ar> = { ar, en };

/** Dialog that collects a mandatory reason (min 5 characters by default) and runs `onSubmit`. */
export function ReasonDialog({
  open,
  onOpenChange,
  title,
  description,
  label,
  submitLabel,
  destructive,
  optional,
  minLength = 5,
  onSubmit,
}: {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  title: string;
  description?: string;
  label: string;
  submitLabel: string;
  destructive?: boolean;
  /** Free note instead of a mandatory reason. */
  optional?: boolean;
  minLength?: number;
  onSubmit: (text: string) => Promise<void>;
}) {
  const { t, dir } = useModuleT(dict);
  const lang = useAuthStore((s) => s.lang);
  const [text, setText] = useState('');
  const [error, setError] = useState<string | undefined>();
  const [serverError, setServerError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (open) {
      setText('');
      setError(undefined);
      setServerError(null);
    }
  }, [open]);
  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const v = text.trim();
    if (!optional && v.length < minLength) return setError(v.length === 0 ? t('required') : t('minChars', { n: minLength }));
    setError(undefined);
    setBusy(true);
    setServerError(null);
    try {
      await onSubmit(v);
      onOpenChange(false);
    } catch (ex) {
      setServerError(mapApiError(ex, lang).message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog open={open} onOpenChange={(o) => !busy && onOpenChange(o)}>
      <DialogContent dir={dir}>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          {description && <DialogDescription>{description}</DialogDescription>}
        </DialogHeader>
        <form onSubmit={submit} noValidate className="space-y-3">
          {serverError && <Banner kind="error">{serverError}</Banner>}
          <FormField label={label} required={!optional} error={error}>
            <Textarea value={text} onChange={(e) => setText(e.target.value)} rows={3} maxLength={1000} />
          </FormField>
          <DialogFooter>
            <Button type="button" variant="outline" disabled={busy} onClick={() => onOpenChange(false)}>
              {t('cancel')}
            </Button>
            <Button type="submit" variant={destructive ? 'destructive' : 'default'} disabled={busy}>
              {busy ? t('working') : submitLabel}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

export interface QtyLine {
  id: string;
  label: string;
  hint?: string;
  /** Upper bound shown as a hint; enforced client-side only when `enforceMax`. */
  max?: number;
  defaultValue?: number;
}

/**
 * Dialog with one quantity input per line (receive, approve, issue, consume, return).
 * Blank lines are skipped, unless `includeZero` (approval: unlisted lines would get 0 anyway).
 * `onSubmit` receives the lines and the optional note/reason text; thrown axios errors are shown inside.
 */
export function LinesDialog({
  open,
  onOpenChange,
  title,
  description,
  lines,
  submitLabel,
  extra,
  enforceMax = true,
  includeZero = false,
  onSubmit,
}: {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  title: string;
  description?: string;
  lines: QtyLine[];
  submitLabel: string;
  extra?: { label: string; required?: boolean; minLength?: number };
  enforceMax?: boolean;
  includeZero?: boolean;
  onSubmit: (lines: { id: string; quantity: number }[], extraText: string) => Promise<void>;
}) {
  const { t, dir } = useModuleT(dict);
  const lang = useAuthStore((s) => s.lang);
  const [vals, setVals] = useState<Record<string, string>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [extraText, setExtraText] = useState('');
  const [serverError, setServerError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open) return;
    setVals(Object.fromEntries(lines.map((l) => [l.id, l.defaultValue !== undefined ? String(l.defaultValue) : ''])));
    setErrors({});
    setExtraText('');
    setServerError(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const err: Record<string, string> = {};
    const out: { id: string; quantity: number }[] = [];
    for (const l of lines) {
      const raw = (vals[l.id] ?? '').trim();
      if (raw === '') continue;
      if (includeZero && Number(raw.replace(',', '.')) === 0) {
        out.push({ id: l.id, quantity: 0 });
        continue;
      }
      const q = parseQty(raw);
      if (q.error) {
        err[l.id] = q.error === 'decimals' ? t('qtyDecimals') : t('qtyInvalid');
        continue;
      }
      if (enforceMax && l.max !== undefined && q.value! > l.max) {
        err[l.id] = t('qtyMax', { max: String(l.max) });
        continue;
      }
      out.push({ id: l.id, quantity: q.value! });
    }
    if (Object.keys(err).length === 0 && !out.some((x) => x.quantity > 0)) err._form = t('atLeastOne');
    const x = extraText.trim();
    if (extra?.required && x.length < (extra.minLength ?? 1)) err._extra = x.length === 0 ? t('required') : t('minChars', { n: extra.minLength ?? 1 });
    setErrors(err);
    if (Object.keys(err).length) return;
    setBusy(true);
    setServerError(null);
    try {
      await onSubmit(out, x);
      onOpenChange(false);
    } catch (ex) {
      setServerError(mapApiError(ex, lang).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !busy && onOpenChange(o)}>
      <DialogContent dir={dir} className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          {description && <DialogDescription>{description}</DialogDescription>}
        </DialogHeader>
        <form onSubmit={submit} noValidate className="space-y-3">
          {serverError && <Banner kind="error">{serverError}</Banner>}
          {errors._form && <Banner kind="error">{errors._form}</Banner>}
          {lines.map((l) => (
            <FormField
              key={l.id}
              label={l.label}
              error={errors[l.id]}
              hint={l.hint ?? (l.max !== undefined ? t('upTo', { max: String(l.max) }) : undefined)}
            >
              <Input
                value={vals[l.id] ?? ''}
                onChange={(e) => setVals((p) => ({ ...p, [l.id]: e.target.value }))}
                inputMode="decimal"
                dir="ltr"
              />
            </FormField>
          ))}
          {extra && (
            <FormField label={extra.label} required={extra.required} error={errors._extra}>
              <Textarea value={extraText} onChange={(e) => setExtraText(e.target.value)} rows={2} maxLength={1000} />
            </FormField>
          )}
          <DialogFooter>
            <Button type="button" variant="outline" disabled={busy} onClick={() => onOpenChange(false)}>
              {t('cancel')}
            </Button>
            <Button type="submit" disabled={busy}>
              {busy ? t('working') : submitLabel}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
