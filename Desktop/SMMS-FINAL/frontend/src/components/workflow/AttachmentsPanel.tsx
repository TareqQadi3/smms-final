'use client';

import { useEffect, useState } from 'react';
import { Download, FileText } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { EmptyState, ErrorState, LoadingState } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { formatDate } from '@/lib/utils';
import { workflowDict } from '@/modules/workflow/i18n';
import { workflowApi } from '@/modules/workflow/api';
import { saveBlob } from '@/modules/workflow/download';
import { describeError } from '@/modules/workflow/errors';
import type { AttachmentMeta } from '@/modules/workflow/types';
import { useLoad } from './useLoad';

const isImage = (a: AttachmentMeta) => !!a.mimeType && a.mimeType.startsWith('image/');

/** Thumbnail fetched through the authorised API (Bearer) and shown from a revocable blob URL. */
function Thumb({ workOrderId, att }: { workOrderId: string; att: AttachmentMeta }) {
  const { t } = useModuleT(workflowDict);
  const [src, setSrc] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    let alive = true;
    let url: string | null = null;
    workflowApi.attachmentBlob(workOrderId, att.id)
      .then((r) => { if (!alive) return; url = URL.createObjectURL(r.data); setSrc(url); })
      .catch(() => { if (alive) setFailed(true); });
    return () => { alive = false; if (url) URL.revokeObjectURL(url); };
  }, [workOrderId, att.id]);
  if (failed) return <div className="flex h-28 items-center justify-center rounded bg-muted text-xs text-muted-foreground">{t('photoUnavailable')}</div>;
  if (!src) return <div className="h-28 animate-pulse rounded bg-muted" aria-hidden />;
  // eslint-disable-next-line @next/next/no-img-element -- blob: URL of an authorised download, next/image cannot optimise it
  return <img src={src} alt={att.fileName} className="h-28 w-full rounded object-cover" />;
}

/** Attachment list with before/after photo gallery and authorised blob downloads. */
export function AttachmentsPanel({ workOrderId, refreshKey = 0 }: { workOrderId: string; refreshKey?: number }) {
  const { t, lang } = useModuleT(workflowDict);
  const list = useLoad(async () => (await workflowApi.attachments(workOrderId)).data, `${workOrderId}:${refreshKey}`);
  const [downloading, setDownloading] = useState<string | null>(null);
  const [dlError, setDlError] = useState('');

  const download = async (a: AttachmentMeta) => {
    setDownloading(a.id); setDlError('');
    try {
      const r = await workflowApi.attachmentBlob(workOrderId, a.id);
      saveBlob(r.data, a.fileName);
    } catch {
      setDlError(`${t('downloadFailed')}: ${a.fileName}`);
    } finally {
      setDownloading(null);
    }
  };

  const items = list.data ?? [];
  const phases: { key: 'before' | 'after'; label: string }[] = [
    { key: 'before', label: t('photosBefore') },
    { key: 'after', label: t('photosAfter') },
  ];
  const others = items.filter((a) => a.photoPhase !== 'before' && a.photoPhase !== 'after');

  const dlButton = (a: AttachmentMeta) => (
    <Button size="sm" variant="outline" className="h-7 gap-1 px-2 text-xs" disabled={downloading === a.id}
      onClick={() => void download(a)} aria-label={`${t('download')}: ${a.fileName}`}>
      <Download size={13} aria-hidden /> {t('download')}
    </Button>
  );

  return (
    <Card>
      <CardHeader className="pb-2"><CardTitle className="text-base">{t('attachments')}</CardTitle></CardHeader>
      <CardContent className="space-y-4">
        {dlError && <p role="alert" className="text-sm text-destructive">{dlError}</p>}
        {list.loading ? (
          <LoadingState rows={2} />
        ) : list.error ? (
          <ErrorState message={describeError(list.error, t)} onRetry={list.reload} />
        ) : items.length === 0 ? (
          <EmptyState title={t('noAttachments')} />
        ) : (
          <>
            {phases.map(({ key, label }) => {
              const group = items.filter((a) => a.photoPhase === key);
              if (group.length === 0) return null;
              return (
                <section key={key} aria-label={label}>
                  <h3 className="mb-2 text-sm font-semibold">{label}</h3>
                  <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3">
                    {group.map((a) => (
                      <li key={a.id} className="space-y-1">
                        {isImage(a) ? <Thumb workOrderId={workOrderId} att={a} /> : (
                          <div className="flex h-28 items-center justify-center rounded bg-muted"><FileText aria-hidden /></div>
                        )}
                        <p className="truncate text-xs" title={a.fileName}>{a.fileName}</p>
                        {dlButton(a)}
                      </li>
                    ))}
                  </ul>
                </section>
              );
            })}
            {others.length > 0 && (
              <section aria-label={t('photosOther')}>
                {items.length !== others.length && <h3 className="mb-2 text-sm font-semibold">{t('photosOther')}</h3>}
                <ul className="divide-y">
                  {others.map((a) => (
                    <li key={a.id} className="flex items-center justify-between gap-3 py-2 text-sm">
                      <div className="min-w-0">
                        <p className="truncate font-medium" title={a.fileName}>{a.fileName}</p>
                        <p className="text-xs text-muted-foreground tabular-nums">{formatDate(a.createdAt, lang, true)}</p>
                      </div>
                      {dlButton(a)}
                    </li>
                  ))}
                </ul>
              </section>
            )}
          </>
        )}
      </CardContent>
    </Card>
  );
}
