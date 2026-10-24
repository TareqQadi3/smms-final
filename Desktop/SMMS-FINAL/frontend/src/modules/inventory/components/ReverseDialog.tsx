'use client';

import { useEffect, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { FormField } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { useAuthStore } from '@/store/authStore';
import { inventoryApi } from '../api';
import { mapApiError } from '../errors';
import { inventoryDict } from '../i18n';
import { useIdempotencyKey } from '../idempotency';
import { Banner, localName } from '../shared';
import type { Movement } from '../types';

/** Reversal of one ledger movement. Reason (5..1000) is mandatory; the Idempotency-Key survives retries. */
export function ReverseDialog({
  movement,
  onClose,
  onDone,
}: {
  movement: Movement | null;
  onClose: () => void;
  onDone: () => void;
}) {
  const { t, dir, isAr } = useModuleT(inventoryDict);
  const lang = useAuthStore((s) => s.lang);
  const { keyFor, done } = useIdempotencyKey();
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | undefined>();
  const [serverError, setServerError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setReason('');
    setError(undefined);
    setServerError(null);
  }, [movement?.id]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!movement) return;
    const r = reason.trim();
    if (r.length < 5) return setError(t('errReason'));
    setError(undefined);
    setBusy(true);
    setServerError(null);
    try {
      await inventoryApi.reverse(movement.id, r, keyFor({ id: movement.id, reason: r }));
      done();
      onDone();
      onClose();
    } catch (ex) {
      setServerError(mapApiError(ex, lang).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={!!movement} onOpenChange={(o) => !o && !busy && onClose()}>
      <DialogContent dir={dir}>
        <DialogHeader>
          <DialogTitle>{t('reverseTitle')}</DialogTitle>
          <DialogDescription>
            {movement ? t('reverseDesc', { kind: t(`kind_${movement.kind}`), qty: String(movement.quantity), item: localName(movement.item, isAr) }) : ''}
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} noValidate className="space-y-3">
          {serverError && <Banner kind="error">{serverError}</Banner>}
          <FormField label={t('reason')} required error={error} hint={t('reasonHint')}>
            <Textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={3} maxLength={1000} />
          </FormField>
          <DialogFooter>
            <Button type="button" variant="outline" disabled={busy} onClick={onClose}>
              {t('cancel')}
            </Button>
            <Button type="submit" variant="destructive" disabled={busy}>
              {busy ? t('saving') : t('reverse')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
