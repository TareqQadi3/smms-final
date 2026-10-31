'use client';

import { useCallback, useEffect, useState } from 'react';
import axios from 'axios';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { FormField, LoadingState } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { techActions, techApi } from '@/modules/tech/api';
import { techDict } from '@/modules/tech/i18n';
import { outstanding, parseQuantity } from '@/modules/tech/rules';
import type { MaterialRequest, MaterialRequestSummary, TechWorkOrder } from '@/modules/tech/types';
import { ActionResult } from './ActionResult';
import { Section } from './Section';
import { useActionRunner } from './useActionRunner';

type Load =
  | { state: 'loading' }
  | { state: 'denied' }
  | { state: 'error' }
  | { state: 'ok'; requests: MaterialRequest[] };

/**
 * Material requests of the ticket and consumption recording (POST /material-requests/{id}/consume,
 * only while the request is `issued`, only by the assigned technician; the server checks both).
 * Calls the backend endpoints directly; creating a request is not offered because technicians
 * cannot list warehouses.
 */
export function MaterialsPanel({ wo, refreshKey, onChanged }: { wo: TechWorkOrder; refreshKey: number; onChanged: () => void }) {
  const { t, lang } = useModuleT(techDict);
  const [load, setLoad] = useState<Load>({ state: 'loading' });

  const fetchAll = useCallback(async () => {
    try {
      const list = (await techApi.materialRequests(wo.id)).data as MaterialRequestSummary[];
      const details = await Promise.all(list.map((m) => techApi.materialRequest(m.id).then((r) => r.data)));
      setLoad({ state: 'ok', requests: details });
    } catch (e) {
      const status = axios.isAxiosError(e) ? e.response?.status : undefined;
      setLoad({ state: status === 403 ? 'denied' : 'error' });
    }
  }, [wo.id]);

  useEffect(() => { void fetchAll(); }, [fetchAll, refreshKey]);

  return (
    <Section title={t('secMaterials')}>
      {load.state === 'loading' && <LoadingState rows={2} />}
      {load.state === 'denied' && <p className="text-sm text-muted-foreground">{t('matUnavailable')}</p>}
      {load.state === 'error' && (
        <div role="alert" className="text-sm text-destructive">
          {t('matLoadError')}{' '}
          <button type="button" className="min-h-11 font-medium underline" onClick={() => { setLoad({ state: 'loading' }); void fetchAll(); }}>{t('retry')}</button>
        </div>
      )}
      {load.state === 'ok' && load.requests.length === 0 && <p className="text-sm text-muted-foreground">{t('matNone')}</p>}
      {load.state === 'ok' && (
        <div className="space-y-3">
          {load.requests.map((mr) => (
            <RequestCard key={mr.id} wo={wo} mr={mr} lang={lang} onChanged={() => { onChanged(); void fetchAll(); }} />
          ))}
          {load.requests.length > 0 && load.requests.every((m) => m.status !== 'issued') && (
            <p className="text-xs text-muted-foreground">{t('matHint')}</p>
          )}
        </div>
      )}
    </Section>
  );
}

function RequestCard({ wo, mr, lang, onChanged }: { wo: TechWorkOrder; mr: MaterialRequest; lang: 'ar' | 'en'; onChanged: () => void }) {
  const { t } = useModuleT(techDict);
  const [qty, setQty] = useState<Record<string, string>>({});
  const [note, setNote] = useState('');
  const [error, setError] = useState<string | undefined>();
  const { run, busy, result } = useActionRunner(onChanged);
  const canConsume = mr.status === 'issued' && mr.lines.some((l) => outstanding(l) > 0);

  const submit = async () => {
    const lines: { lineId: string; quantity: number }[] = [];
    for (const line of mr.lines) {
      const raw = (qty[line.id] ?? '').trim();
      if (!raw) continue;
      const name = lang === 'ar' && line.item.nameAr ? line.item.nameAr : line.item.name;
      const q = parseQuantity(raw);
      if (q === null) { setError(t('matBadQty', { item: name })); return; }
      const max = outstanding(line);
      if (q > max) { setError(t('matTooMuch', { item: name, max })); return; }
      lines.push({ lineId: line.id, quantity: q });
    }
    if (lines.length === 0) { setError(t('matNothing')); return; }
    setError(undefined);
    const outcome = await run(techActions.consume(wo, mr, lines, note));
    if (outcome && outcome.state !== 'failed') { setQty({}); setNote(''); }
  };

  return (
    <div className="rounded-lg border border-border p-2.5" data-testid="material-request">
      <p className="text-sm font-semibold">
        <bdi dir="ltr">{mr.requestNumber}</bdi>
        <span className="ms-2 text-xs font-normal text-muted-foreground">{t('matStatus', { status: mr.status })}</span>
      </p>
      <ul className="mt-2 space-y-3">
        {mr.lines.map((line) => {
          const name = lang === 'ar' && line.item.nameAr ? line.item.nameAr : line.item.name;
          const left = outstanding(line);
          return (
            <li key={line.id} className="text-sm">
              <p className="font-medium">{name} <span className="text-xs text-muted-foreground"><bdi dir="ltr">{line.item.sku}</bdi></span></p>
              <p className="text-xs text-muted-foreground">
                {t('matIssued')}: {line.qtyIssued} / {t('matConsumed')}: {line.qtyConsumed} / {t('matReturned')}: {line.qtyReturned} / {t('matOutstanding')}: {left} {line.item.uom ?? ''}
              </p>
              {canConsume && left > 0 && (
                <div className="mt-1.5">
                  <label className="sr-only" htmlFor={`qty-${line.id}`}>{t('matQty', { item: name })}</label>
                  <Input
                    id={`qty-${line.id}`}
                    inputMode="decimal"
                    autoComplete="off"
                    dir="ltr"
                    className="h-11 max-md:h-11"
                    placeholder={`0 - ${left}`}
                    value={qty[line.id] ?? ''}
                    disabled={busy}
                    onChange={(e) => { setQty((q) => ({ ...q, [line.id]: e.target.value })); if (error) setError(undefined); }}
                  />
                </div>
              )}
            </li>
          );
        })}
      </ul>
      {canConsume && (
        <div className="mt-3 space-y-2">
          <FormField label={t('matNoteLabel')} error={error}>
            <Textarea rows={2} value={note} onChange={(e) => setNote(e.target.value)} disabled={busy} maxLength={1000} />
          </FormField>
          <Button className="h-11 min-h-11 w-full" disabled={busy} onClick={() => void submit()}>
            {busy ? t('sending') : t('matConsume')}
          </Button>
          <ActionResult result={result} />
        </div>
      )}
    </div>
  );
}
