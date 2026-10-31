'use client';

import { useEffect, useRef, useState } from 'react';
import { Camera, ImageIcon, FileText } from 'lucide-react';
import { LoadingState } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { techActions, techApi } from '@/modules/tech/api';
import { formatBytes } from '@/modules/tech/format';
import { techDict } from '@/modules/tech/i18n';
import { PhotoError, compressImage } from '@/modules/tech/image';
import type { Attachment, PhotoPhase, TechWorkOrder } from '@/modules/tech/types';
import { cn } from '@/lib/utils';
import { ActionResult } from './ActionResult';
import { Section } from './Section';
import { useActionRunner } from './useActionRunner';

/**
 * Before/after photos. Camera or gallery, compressed on the device to at most 2 MB JPEG, uploaded
 * as POST /attachments?phase=before|after with an Idempotency-Key through the offline queue, so a
 * retry after a lost connection never creates a duplicate.
 */
export function PhotosPanel({
  wo, attachments, loading, failed, onChanged,
}: { wo: TechWorkOrder; attachments: Attachment[] | undefined; loading: boolean; failed: boolean; onChanged: () => void }) {
  const { t } = useModuleT(techDict);
  const list = attachments ?? [];
  const phases: PhotoPhase[] = ['before', 'after'];
  const other = list.filter((a) => !a.photoPhase);

  return (
    <Section title={t('secPhotos')}>
      {loading && attachments === undefined && <LoadingState rows={2} />}
      {failed && attachments === undefined && <p role="alert" className="text-sm text-destructive">{t('photoLoadError')}</p>}
      <div className="space-y-4">
        {phases.map((phase) => (
          <PhaseGroup key={phase} wo={wo} phase={phase} items={list.filter((a) => a.photoPhase === phase)} onChanged={onChanged} />
        ))}
        {other.length > 0 && (
          <div>
            <h4 className="mb-1.5 text-sm font-medium">{t('photoOther')}</h4>
            <ul className="flex flex-wrap gap-2">
              {other.map((a) => <li key={a.id}><Thumb woId={wo.id} att={a} phaseLabel="" /></li>)}
            </ul>
          </div>
        )}
      </div>
    </Section>
  );
}

function PhaseGroup({ wo, phase, items, onChanged }: { wo: TechWorkOrder; phase: PhotoPhase; items: Attachment[]; onChanged: () => void }) {
  const { t } = useModuleT(techDict);
  const [prep, setPrep] = useState(false);
  const [photoError, setPhotoError] = useState<string | null>(null);
  const { run, busy, result } = useActionRunner(onChanged);
  const label = phase === 'before' ? t('photoBefore') : t('photoAfter');

  const onPick = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = ''; // the same file can be picked again
    if (!file) return;
    setPhotoError(null);
    setPrep(true);
    try {
      const jpeg = await compressImage(file);
      await run(techActions.photo(wo, jpeg, phase));
    } catch (err) {
      const code = err instanceof PhotoError ? err.code : 'unsupported';
      setPhotoError(code === 'not-image' ? t('photoNotImage') : code === 'too-large' ? t('photoTooLarge') : t('photoUnsupported'));
    } finally {
      setPrep(false);
    }
  };

  const btn = 'inline-flex min-h-11 flex-1 cursor-pointer items-center justify-center gap-2 rounded-lg border border-border bg-background px-3 text-sm font-medium has-[:focus-visible]:ring-3 has-[:focus-visible]:ring-ring/50';
  const disabled = busy || prep;

  return (
    <div role="group" aria-label={t('photoGroup', { phase: label })} data-testid={`photos-${phase}`}>
      <h4 className="mb-1.5 text-sm font-medium">{label}</h4>
      {items.length === 0 ? (
        <p className="mb-2 text-xs text-muted-foreground">{t('photoNone')}</p>
      ) : (
        <ul className="mb-2 flex flex-wrap gap-2">
          {items.map((a) => <li key={a.id}><Thumb woId={wo.id} att={a} phaseLabel={label} /></li>)}
        </ul>
      )}
      <div className="flex gap-2">
        <label className={cn(btn, disabled && 'pointer-events-none opacity-50')}>
          <Camera size={18} aria-hidden="true" />
          <span>{t('photoTake')}</span>
          <input type="file" accept="image/*" capture="environment" className="sr-only" disabled={disabled} onChange={onPick} data-testid={`take-${phase}`} />
          <span className="sr-only">({label})</span>
        </label>
        <label className={cn(btn, disabled && 'pointer-events-none opacity-50')}>
          <ImageIcon size={18} aria-hidden="true" />
          <span>{t('photoChoose')}</span>
          <input type="file" accept="image/*" className="sr-only" disabled={disabled} onChange={onPick} data-testid={`choose-${phase}`} />
          <span className="sr-only">({label})</span>
        </label>
      </div>
      <div className="mt-2 space-y-1.5" aria-live="polite">
        {prep && <p role="status" className="text-xs text-muted-foreground">{t('photoCompressing')}</p>}
        {photoError && <p role="alert" className="rounded-lg border border-red-300 bg-red-50 px-3 py-2 text-sm text-red-900">{photoError}</p>}
        <ActionResult result={result && result.kind === 'ok' ? { kind: 'ok', text: t('photoUploaded') } : result && result.kind === 'queued' ? { kind: 'queued', text: t('photoQueued') } : result} />
      </div>
    </div>
  );
}

/** Image thumbnail loaded as a blob with the Bearer header (attachments are never a public URL). */
function Thumb({ woId, att, phaseLabel }: { woId: string; att: Attachment; phaseLabel: string }) {
  const { t } = useModuleT(techDict);
  const isImage = att.mimeType?.startsWith('image/');
  const [url, setUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const created = useRef<string | null>(null);

  useEffect(() => {
    if (!isImage) return;
    let cancelled = false;
    techApi.attachmentBlob(woId, att.id)
      .then((r) => {
        if (cancelled) return;
        created.current = URL.createObjectURL(r.data);
        setUrl(created.current);
      })
      .catch(() => { if (!cancelled) setFailed(true); });
    return () => {
      cancelled = true;
      if (created.current) URL.revokeObjectURL(created.current);
    };
  }, [woId, att.id, isImage]);

  const box = 'flex size-24 flex-col items-center justify-center gap-1 overflow-hidden rounded-lg border border-border bg-muted text-center text-[11px] text-muted-foreground';
  if (url) {
    return (
      <a href={url} target="_blank" rel="noopener noreferrer" className={cn(box, 'outline-none focus-visible:ring-3 focus-visible:ring-ring/50')}>
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src={url} alt={t('photoAlt', { phase: phaseLabel, name: att.fileName })} className="size-full object-cover" />
      </a>
    );
  }
  return (
    <div className={box} title={att.fileName}>
      <FileText size={22} aria-hidden="true" />
      <span className="line-clamp-2 px-1 break-all">{failed || !isImage ? att.fileName : t('photoNoPreview')}</span>
      <span>{formatBytes(att.fileSize)}</span>
    </div>
  );
}
