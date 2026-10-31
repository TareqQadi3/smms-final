'use client';

import { useCallback, useEffect, useState } from 'react';
import axios from 'axios';
import { Globe, RefreshCw } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { LoadingState } from '@/components/common';
import { useAuthStore } from '@/store/authStore';
import { useModuleT } from '@/lib/module-i18n';
import { cn, formatDate } from '@/lib/utils';
import { publicStatusApi } from '@/modules/requests/api';
import { requestsDict } from '@/modules/requests/i18n';
import type { PublicStatus } from '@/modules/requests/types';

type State =
  | { s: 'loading' }
  | { s: 'ok'; data: PublicStatus }
  | { s: 'notfound' }
  | { s: 'toomany' }
  | { s: 'error' };

const GROUP_STYLE: Record<PublicStatus['statusGroup'], string> = {
  received: 'bg-blue-100 text-blue-900',
  in_progress: 'bg-orange-100 text-orange-900',
  waiting: 'bg-amber-100 text-amber-900',
  done: 'bg-green-100 text-green-900',
  cancelled: 'bg-red-100 text-red-900',
};

/** Shows only what the public endpoint returns. 404 (unknown, expired, revoked, disabled) is one message. */
export function PublicStatusView({ token }: { token: string }) {
  const { t, lang, dir } = useModuleT(requestsDict);
  const [state, setState] = useState<State>({ s: 'loading' });
  const { init, setLang } = useAuthStore();

  useEffect(() => { init(); }, [init]);

  const load = useCallback(() => {
    publicStatusApi.get(token)
      .then((r) => setState({ s: 'ok', data: r.data }))
      .catch((e) => {
        const status = axios.isAxiosError(e) ? e.response?.status : undefined;
        setState(status === 404 ? { s: 'notfound' } : status === 429 ? { s: 'toomany' } : { s: 'error' });
      });
  }, [token]);

  useEffect(() => { load(); }, [load]);
  // Refresh every minute while the request is still open (the endpoint allows 30 requests per minute)
  useEffect(() => {
    if (state.s !== 'ok' || state.data.completed) return;
    const h = setInterval(load, 60_000);
    return () => clearInterval(h);
  }, [state, load]);

  const steps = [
    { id: 'received', label: t('stepReceived') },
    { id: 'in_progress', label: t('stepProgress') },
    { id: 'done', label: t('stepDone') },
  ];
  const stepIndex = (g: PublicStatus['statusGroup']) => (g === 'received' ? 0 : g === 'done' ? 2 : 1);

  return (
    <div dir={dir} className="min-h-screen bg-background text-foreground">
      <header className="flex h-14 items-center justify-between border-b border-border bg-card px-4">
        <h1 className="text-base font-semibold">{t('pubTitle')}</h1>
        <button
          type="button"
          onClick={() => setLang(lang === 'ar' ? 'en' : 'ar')}
          aria-label={t('switchLang')}
          className="inline-flex min-h-11 items-center gap-1.5 rounded-lg px-3 text-sm outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50"
        >
          <Globe size={16} aria-hidden="true" /> {t('switchLang')}
        </button>
      </header>

      <main className="mx-auto w-full max-w-md space-y-4 px-4 py-6">
        {state.s === 'loading' && <LoadingState rows={3} label={t('pubLoading')} />}

        {state.s === 'notfound' && (
          <div role="alert" className="rounded-xl border border-border bg-card p-5 text-center">
            <p className="font-semibold">{t('pubNotFound')}</p>
            <p className="mt-1 text-sm text-muted-foreground">{t('pubNotFoundBody')}</p>
          </div>
        )}

        {(state.s === 'toomany' || state.s === 'error') && (
          <div role="alert" className="rounded-xl border border-red-300 bg-red-50 p-5 text-center text-red-900">
            <p className="font-medium">{state.s === 'toomany' ? t('pubTooMany') : t('pubError')}</p>
            <Button variant="outline" className="mt-3 h-11 min-h-11" onClick={() => { setState({ s: 'loading' }); load(); }}>{t('pubRefresh')}</Button>
          </div>
        )}

        {state.s === 'ok' && (
          <div className="space-y-4 rounded-xl border border-border bg-card p-5 shadow-sm" data-testid="public-status">
            <div>
              <p className="text-xs text-muted-foreground">{t('pubTicket')}</p>
              <p className="font-mono text-xl font-bold tabular-nums"><bdi dir="ltr">{state.data.ticketNumber}</bdi></p>
            </div>
            <p role="status" className={cn('inline-flex rounded-full px-3 py-1.5 text-sm font-semibold', GROUP_STYLE[state.data.statusGroup] ?? 'bg-gray-100 text-gray-900')}>
              {t(`group_${state.data.statusGroup}` as 'group_received')}
            </p>
            {state.data.statusGroup !== 'cancelled' && (
              <ol aria-label={t('statusProgress')} className="flex items-center gap-2">
                {steps.map((s, i) => {
                  const cur = stepIndex(state.data.statusGroup);
                  return (
                    <li key={s.id} aria-current={i === cur ? 'step' : undefined} className="flex flex-1 flex-col items-center gap-1 text-center">
                      <span className={cn('h-2 w-full rounded-full', i <= cur ? 'bg-primary' : 'bg-muted')} />
                      <span className={cn('text-xs', i === cur ? 'font-semibold' : 'text-muted-foreground')}>{s.label}</span>
                    </li>
                  );
                })}
              </ol>
            )}
            <dl className="grid grid-cols-2 gap-3 text-sm">
              <div><dt className="text-xs text-muted-foreground">{t('pubCreated')}</dt><dd>{formatDate(state.data.createdAt, lang, true)}</dd></div>
              <div><dt className="text-xs text-muted-foreground">{t('pubUpdated')}</dt><dd>{formatDate(state.data.lastUpdatedAt, lang, true)}</dd></div>
            </dl>
            <Button variant="outline" className="h-11 min-h-11 w-full" onClick={load}>
              <RefreshCw size={16} aria-hidden="true" /> {t('pubRefresh')}
            </Button>
            <p className="text-center text-xs text-muted-foreground">{t('pubPrivacy')}</p>
          </div>
        )}
      </main>
    </div>
  );
}
