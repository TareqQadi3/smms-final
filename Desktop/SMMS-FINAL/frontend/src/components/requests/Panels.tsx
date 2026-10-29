'use client';

import { useCallback, useEffect, useState } from 'react';
import axios from 'axios';
import { Check, Copy, Link2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { ConfirmDialog, FormField, LoadingState } from '@/components/common';
import { Section } from '@/components/tech/Section';
import { useModuleT } from '@/lib/module-i18n';
import { formatDate } from '@/lib/utils';
import { requestsApi } from '@/modules/requests/api';
import { describeRequestError, type DescribedError } from '@/modules/requests/errors';
import { requestsDict } from '@/modules/requests/i18n';
import { absoluteStatusUrl, openInfoRequest } from '@/modules/requests/rules';
import type { InfoRequest, Rating, RequestWorkOrder, StatusLink } from '@/modules/requests/types';
import { StarRating } from './StarRating';
import { useIdempotentSubmit } from './useIdempotentSubmit';

export const ANSWER_MAX = 2000;
export const COMMENT_MAX = 1000;

function ErrorBox({ error, serverSays }: { error: DescribedError | null; serverSays: string }) {
  if (!error) return null;
  return (
    <div role="alert" className="rounded-lg border border-red-300 bg-red-50 px-3 py-2 text-sm text-red-900">
      <p className="font-medium">{error.text}</p>
      {error.detail && <p className="mt-0.5 text-xs"><bdi dir="auto">{serverSays}</bdi></p>}
    </div>
  );
}

// ── Information request ────────────────────────────────────

/** Shows the open question (and earlier ones) and sends the answer with POST /provide-info. */
export function InfoRequestPanel({ wo, active, onChanged }: { wo: RequestWorkOrder; active: boolean; onChanged: () => void }) {
  const { t, lang } = useModuleT(requestsDict);
  const [list, setList] = useState<InfoRequest[] | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [answer, setAnswer] = useState('');
  const [fieldError, setFieldError] = useState<string | undefined>();
  const [error, setError] = useState<DescribedError | null>(null);
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState(false);

  const load = useCallback(() => {
    requestsApi.infoRequests(wo.id)
      .then((r) => { setList(Array.isArray(r.data) ? r.data : []); setLoadFailed(false); })
      .catch(() => setLoadFailed(true));
  }, [wo.id]);
  useEffect(() => { load(); }, [load, wo.currentStage]);

  const open = list ? openInfoRequest(list) : undefined;
  const past = (list ?? []).filter((r) => r !== open);
  if (!active && past.length === 0 && !loadFailed) return null;

  const submit = async () => {
    const text = answer.trim();
    if (!text) { setFieldError(t('infoRequired')); return; }
    if (text.length > ANSWER_MAX) { setFieldError(t('infoTooLong', { max: ANSWER_MAX })); return; }
    setFieldError(undefined);
    setError(null);
    setBusy(true);
    try {
      await requestsApi.provideInfo(wo.id, text);
      setSent(true);
      setAnswer('');
      onChanged();
      load();
    } catch (e) {
      setError(describeRequestError(e, t));
      if (axios.isAxiosError(e) && (e.response?.status === 422 || e.response?.status === 409)) { onChanged(); load(); }
    } finally {
      setBusy(false);
    }
  };

  return (
    <Section title={t('secInfo')}>
      <div className="space-y-3">
        {active && open && (
          <div className="space-y-2.5">
            <div className="rounded-lg bg-amber-50 p-2.5 text-sm text-amber-950">
              <p className="text-xs font-semibold">{t('infoQuestion')}</p>
              <p className="mt-0.5 whitespace-pre-wrap break-words"><bdi dir="auto">{open.question}</bdi></p>
            </div>
            <FormField label={t('infoAnswerLabel')} required hint={t('infoAnswerHint')} error={fieldError}>
              <Textarea rows={4} value={answer} maxLength={ANSWER_MAX + 200} placeholder={t('infoAnswerPlaceholder')} disabled={busy}
                onChange={(e) => { setAnswer(e.target.value); if (fieldError) setFieldError(undefined); }} />
            </FormField>
            <ErrorBox error={error} serverSays={t('errServerSays', { message: error?.detail ?? '' })} />
            <Button className="h-11 min-h-11 w-full" disabled={busy} onClick={() => void submit()}>
              {busy ? t('working') : t('infoSend')}
            </Button>
          </div>
        )}
        {sent && !open && <p role="status" className="rounded-lg border border-green-300 bg-green-50 px-3 py-2 text-sm text-green-900">{t('infoSent')}</p>}
        {loadFailed && <p role="alert" className="text-sm text-destructive">{t('loadError')}</p>}
        {list === null && !loadFailed && <LoadingState rows={1} />}
        {past.length > 0 && (
          <div>
            <h4 className="mb-1.5 text-sm font-medium text-muted-foreground">{t('infoHistory')}</h4>
            <ul className="space-y-2">
              {past.map((r) => (
                <li key={r.id} className="rounded-lg bg-muted/50 p-2.5 text-sm" data-testid="info-history">
                  <p className="whitespace-pre-wrap break-words"><bdi dir="auto">{r.question}</bdi></p>
                  {r.answer && <p className="mt-1 whitespace-pre-wrap break-words border-s-2 border-primary ps-2"><bdi dir="auto">{r.answer}</bdi></p>}
                  <p className="mt-1 text-xs text-muted-foreground">
                    {r.cancelledAt ? t('infoCancelled') : t('infoAnswered')} - {formatDate(r.answeredAt ?? r.cancelledAt ?? r.requestedAt, lang, true)}
                  </p>
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </Section>
  );
}

// ── Confirmation ───────────────────────────────────────────

/** Accept or dispute the finished work (POST /confirm with an Idempotency-Key; a note is required to dispute). */
export function ConfirmationPanel({ wo, onChanged }: { wo: RequestWorkOrder; onChanged: () => void }) {
  const { t, lang, dir } = useModuleT(requestsDict);
  const { run, reset } = useIdempotentSubmit();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<DescribedError | null>(null);
  const [dispute, setDispute] = useState(false);
  const [note, setNote] = useState('');
  const [noteError, setNoteError] = useState<string | undefined>();

  if (wo.requesterConfirmation) {
    return (
      <Section title={t('secConfirm')}>
        <p role="status" className="text-sm">
          {wo.requesterConfirmation === 'accepted'
            ? t('confirmedAccepted', { date: formatDate(wo.requesterConfirmedAt, lang, true) })
            : t('confirmedDisputed', { date: formatDate(wo.requesterConfirmedAt, lang, true) })}
        </p>
      </Section>
    );
  }
  if (wo.status !== 'completed') return null;

  const send = async (accepted: boolean, text?: string) => {
    setBusy(true);
    setError(null);
    try {
      await run((key) => requestsApi.confirm(wo.id, accepted, text, key));
      setDispute(false);
      setNote('');
      onChanged();
    } catch (e) {
      setError(describeRequestError(e, t));
      if (axios.isAxiosError(e) && (e.response?.status === 422 || e.response?.status === 409)) onChanged();
    } finally {
      setBusy(false);
    }
  };

  const submitDispute = () => {
    const text = note.trim();
    if (!text) { setNoteError(t('disputeRequired')); return; }
    if (text.length > COMMENT_MAX) { setNoteError(t('disputeTooLong', { max: COMMENT_MAX })); return; }
    setNoteError(undefined);
    void send(false, text);
  };

  return (
    <Section title={t('secConfirm')}>
      <div className="space-y-2.5">
        <p className="text-sm">{t('confirmBody')}</p>
        {wo.techReport && (
          <div className="rounded-lg bg-muted/50 p-2.5 text-sm">
            <p className="text-xs font-semibold text-muted-foreground">{t('fieldReport')}</p>
            <p className="mt-0.5 whitespace-pre-wrap break-words"><bdi dir="auto">{wo.techReport}</bdi></p>
          </div>
        )}
        {!dispute && <ErrorBox error={error} serverSays={t('errServerSays', { message: error?.detail ?? '' })} />}
        <div className="flex flex-col gap-2 sm:flex-row">
          <Button className="h-12 min-h-12 flex-1 text-base" disabled={busy} onClick={() => void send(true)}>
            <Check size={18} aria-hidden="true" /> {busy && !dispute ? t('working') : t('confirmAccept')}
          </Button>
          <Button variant="outline" className="h-12 min-h-12 flex-1 text-base" disabled={busy} onClick={() => { reset(); setError(null); setDispute(true); }}>
            {t('confirmDispute')}
          </Button>
        </div>
      </div>

      <Dialog open={dispute} onOpenChange={(o) => { if (!o && !busy) setDispute(false); }}>
        <DialogContent dir={dir}>
          <DialogHeader>
            <DialogTitle>{t('disputeTitle')}</DialogTitle>
            <DialogDescription>{t('disputeBody')}</DialogDescription>
          </DialogHeader>
          <FormField label={t('disputeNote')} required error={noteError}>
            <Textarea rows={4} value={note} maxLength={COMMENT_MAX + 200} disabled={busy}
              onChange={(e) => { setNote(e.target.value); if (noteError) setNoteError(undefined); }} />
          </FormField>
          <ErrorBox error={error} serverSays={t('errServerSays', { message: error?.detail ?? '' })} />
          <DialogFooter>
            <Button variant="outline" className="h-11 min-h-11" disabled={busy} onClick={() => setDispute(false)}>{t('cancel')}</Button>
            <Button className="h-11 min-h-11" disabled={busy} onClick={submitDispute}>{busy ? t('working') : t('disputeSubmit')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </Section>
  );
}

// ── Rating ─────────────────────────────────────────────────

/** GET /rating (404 = not rated yet) and POST /rating (409 = already rated, 422 = not eligible or window over). */
export function RatingPanel({ wo }: { wo: RequestWorkOrder }) {
  const { t } = useModuleT(requestsDict);
  const [rating, setRating] = useState<Rating | null | undefined>(undefined); // undefined = loading, null = none
  const [loadFailed, setLoadFailed] = useState(false);
  const [score, setScore] = useState(0);
  const [comment, setComment] = useState('');
  const [fieldError, setFieldError] = useState<string | undefined>();
  const [error, setError] = useState<DescribedError | null>(null);
  const [busy, setBusy] = useState(false);
  const [justRated, setJustRated] = useState(false);

  const load = useCallback(() => {
    requestsApi.getRating(wo.id)
      .then((r) => { setRating(r.data); setLoadFailed(false); })
      .catch((e) => {
        if (axios.isAxiosError(e) && e.response?.status === 404) { setRating(null); setLoadFailed(false); }
        else setLoadFailed(true);
      });
  }, [wo.id]);
  useEffect(() => { load(); }, [load]);

  const submit = async () => {
    if (score < 1) { setFieldError(t('ratingRequired')); return; }
    if (comment.trim().length > COMMENT_MAX) { setFieldError(t('ratingTooLong', { max: COMMENT_MAX })); return; }
    setFieldError(undefined);
    setError(null);
    setBusy(true);
    try {
      const res = await requestsApi.rate(wo.id, score, comment.trim() || undefined);
      setRating(res.data);
      setJustRated(true);
    } catch (e) {
      if (axios.isAxiosError(e) && e.response?.status === 409) load(); // already rated: show it
      else setError(describeRequestError(e, t));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Section title={t('secRating')}>
      {rating === undefined && !loadFailed && <LoadingState rows={1} />}
      {loadFailed && (
        <p role="alert" className="text-sm text-destructive">
          {t('ratingLoadError')} <button type="button" className="min-h-11 font-medium underline" onClick={load}>{t('retry')}</button>
        </p>
      )}
      {rating && (
        <div className="space-y-1.5">
          {justRated && <p role="status" className="text-sm text-green-800">{t('ratingThanks')}</p>}
          <StarRating readOnly value={rating.score} label={t('ratingScore')} starLabel={(n) => t('ratingYours', { n })} />
          {rating.comment && <p className="whitespace-pre-wrap break-words text-sm"><bdi dir="auto">{rating.comment}</bdi></p>}
        </div>
      )}
      {rating === null && (
        <div className="space-y-2.5">
          <p className="text-sm">{t('ratingPrompt')}</p>
          <StarRating value={score} onChange={(n) => { setScore(n); setFieldError(undefined); }} label={t('ratingScore')} starLabel={(n) => t('ratingStar', { n })} disabled={busy} />
          <FormField label={t('ratingComment')} error={fieldError} hint={t('ratingWindow')}>
            <Textarea rows={3} value={comment} maxLength={COMMENT_MAX + 200} disabled={busy} onChange={(e) => setComment(e.target.value)} />
          </FormField>
          <ErrorBox error={error} serverSays={t('errServerSays', { message: error?.detail ?? '' })} />
          <Button className="h-11 min-h-11 w-full" disabled={busy} onClick={() => void submit()}>{busy ? t('working') : t('ratingSubmit')}</Button>
        </div>
      )}
    </Section>
  );
}

// ── Status link ────────────────────────────────────────────

/** POST /status-link: the token is shown once; a new link revokes the previous one. */
export function StatusLinkPanel({ wo }: { wo: RequestWorkOrder }) {
  const { t, lang } = useModuleT(requestsDict);
  const [link, setLink] = useState<StatusLink | null>(null);
  const [created, setCreated] = useState(false);
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<DescribedError | null>(null);
  const [copied, setCopied] = useState<'yes' | 'failed' | null>(null);

  const create = async () => {
    setBusy(true);
    setError(null);
    setCopied(null);
    try {
      const res = await requestsApi.createStatusLink(wo.id);
      setLink(res.data);
      setCreated(true);
      setConfirm(false);
    } catch (e) {
      const d = describeRequestError(e, t);
      // "Public status links are disabled" is a 422 with that exact message
      setError(d.status === 422 && d.detail?.toLowerCase().includes('disabled') ? { text: t('linkDisabled') } : d);
      setConfirm(false);
    } finally {
      setBusy(false);
    }
  };

  const url = link ? absoluteStatusUrl(link.path, window.location.origin) : '';

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(url);
      setCopied('yes');
    } catch {
      setCopied('failed');
    }
  };

  return (
    <Section title={t('secLink')}>
      <div className="space-y-2.5">
        <p className="text-sm text-muted-foreground">{t('linkBody')}</p>
        {link && (
          <div className="space-y-2">
            <p role="status" className="text-xs font-medium text-amber-800">{t('linkOnce')}</p>
            <label className="text-xs text-muted-foreground" htmlFor="status-link-url">{t('linkLabel')}</label>
            <Input id="status-link-url" readOnly dir="ltr" value={url} className="h-11 max-md:h-11 font-mono text-xs" onFocus={(e) => e.currentTarget.select()} />
            <p className="text-xs text-muted-foreground">{t('linkExpires', { date: formatDate(link.expiresAt, lang, true) })}</p>
            <Button variant="outline" className="h-11 min-h-11 w-full" onClick={() => void copy()}>
              <Copy size={16} aria-hidden="true" /> {copied === 'yes' ? t('linkCopied') : t('linkCopy')}
            </Button>
            {copied === 'failed' && <p role="alert" className="text-xs text-destructive">{t('linkCopyFail')}</p>}
          </div>
        )}
        {error && <ErrorBox error={error} serverSays={t('errServerSays', { message: error.detail ?? '' })} />}
        <Button className="h-11 min-h-11 w-full" disabled={busy} onClick={() => (created ? setConfirm(true) : void create())}>
          <Link2 size={16} aria-hidden="true" /> {busy ? t('working') : created ? t('linkNew') : t('linkCreate')}
        </Button>
      </div>
      <ConfirmDialog
        open={confirm}
        onOpenChange={setConfirm}
        title={t('linkConfirmTitle')}
        description={t('linkConfirmBody')}
        confirmLabel={t('linkConfirm')}
        cancelLabel={t('cancel')}
        loading={busy}
        onConfirm={create}
      />
    </Section>
  );
}
