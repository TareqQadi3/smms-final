'use client';

import { useModuleT } from '@/lib/module-i18n';
import { tStatus } from '@/lib/i18n';
import { formatDate } from '@/lib/utils';
import { techDict } from '@/modules/tech/i18n';
import type { HistoryEntry } from '@/modules/tech/types';
import { Section } from './Section';

export function TimelinePanel({ history, failed }: { history: HistoryEntry[] | undefined; failed: boolean }) {
  const { t, lang } = useModuleT(techDict);
  return (
    <Section title={t('secTimeline')}>
      {failed && history === undefined && <p role="alert" className="text-sm text-destructive">{t('timelineError')}</p>}
      {history && history.length === 0 && <p className="text-sm text-muted-foreground">{t('timelineNone')}</p>}
      {history && history.length > 0 && (
        <ol className="space-y-2 border-s-2 border-border ps-3">
          {history.slice().reverse().map((h) => (
            <li key={h.id} className="text-sm">
              <p className="font-medium">
                {h.fromStatus && h.fromStatus !== h.toStatus ? `${tStatus(h.fromStatus, lang)} → ${tStatus(h.toStatus, lang)}` : tStatus(h.toStatus, lang)}
              </p>
              {h.note && <p className="whitespace-pre-wrap break-words text-xs text-muted-foreground"><bdi dir="auto">{h.note}</bdi></p>}
              <p className="text-xs text-muted-foreground">{h.actor?.fullName} - {formatDate(h.createdAt, lang, true)}</p>
            </li>
          ))}
        </ol>
      )}
    </Section>
  );
}
