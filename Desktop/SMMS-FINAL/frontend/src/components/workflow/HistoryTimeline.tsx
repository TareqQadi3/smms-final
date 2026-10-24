'use client';

import { CheckCircle, Clock, MessageSquare, User, XCircle } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { EmptyState } from '@/components/common';
import { statusColors, tStatus } from '@/lib/i18n';
import { useModuleT } from '@/lib/module-i18n';
import { formatDate } from '@/lib/utils';
import { workflowDict } from '@/modules/workflow/i18n';
import type { HistoryEntry } from '@/modules/workflow/types';

/** Work order history: status changes and notes (stage "note", from = to = current status). */
export function HistoryTimeline({ history }: { history: HistoryEntry[] }) {
  const { t, lang, isAr } = useModuleT(workflowDict);
  return (
    <Card>
      <CardHeader className="pb-2"><CardTitle className="text-base">{t('history')}</CardTitle></CardHeader>
      <CardContent>
        {history.length === 0 ? (
          <EmptyState title={t('none')} />
        ) : (
          <ol>
            {history.map((h, i) => {
              const isNote = h.stage === 'note';
              const done = h.toStatus === 'completed' || h.toStatus === 'closed';
              return (
                <li key={h.id} className="flex gap-3">
                  <div className="flex flex-col items-center">
                    <div className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-full ${
                      isNote ? 'bg-slate-100 text-slate-600' : done ? 'bg-green-100 text-green-600'
                        : h.toStatus === 'cancelled' ? 'bg-red-100 text-red-600' : 'bg-blue-100 text-blue-600'}`}>
                      {isNote ? <MessageSquare size={16} aria-hidden /> : done ? <CheckCircle size={16} aria-hidden />
                        : h.toStatus === 'cancelled' ? <XCircle size={16} aria-hidden /> : <Clock size={16} aria-hidden />}
                    </div>
                    {i < history.length - 1 && <div className="my-1 min-h-[20px] w-0.5 flex-1 bg-border" />}
                  </div>
                  <div className="min-w-0 flex-1 pb-4">
                    <div className="flex flex-wrap items-center gap-2">
                      {isNote ? (
                        <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-700">{t('noteBadge')}</span>
                      ) : (
                        <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${statusColors[h.toStatus] || 'bg-gray-100'}`}>
                          {tStatus(h.toStatus, lang)}
                        </span>
                      )}
                      {!isNote && h.fromStatus && (
                        <span className="text-xs text-muted-foreground">{isAr ? 'من:' : 'from:'} {tStatus(h.fromStatus, lang)}</span>
                      )}
                    </div>
                    {h.note && <p className="mt-1 whitespace-pre-wrap text-sm text-foreground">{h.note}</p>}
                    <div className="mt-1 flex gap-3 text-xs text-muted-foreground">
                      {h.actor && <span className="flex items-center gap-1"><User size={11} aria-hidden />{h.actor.fullName}</span>}
                      <span className="tabular-nums">{formatDate(h.createdAt, lang, true, '')}</span>
                    </div>
                  </div>
                </li>
              );
            })}
          </ol>
        )}
      </CardContent>
    </Card>
  );
}
