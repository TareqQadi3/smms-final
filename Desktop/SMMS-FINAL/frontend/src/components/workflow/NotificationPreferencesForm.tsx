'use client';

import { useEffect, useState } from 'react';
import { AlertTriangle, CheckCircle2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { ErrorState, LoadingState } from '@/components/common';
import { useModuleT } from '@/lib/module-i18n';
import { workflowDict } from '@/modules/workflow/i18n';
import { notificationPrefsApi } from '@/modules/workflow/api';
import { describeError } from '@/modules/workflow/errors';
import type { NotificationPreferences } from '@/modules/workflow/types';
import { useLoad } from './useLoad';

function ChannelRow({
  id, label, checked, onChange, available, availableText, unavailableText, disabled,
}: {
  id: string; label: string; checked: boolean; onChange: (v: boolean) => void; available: boolean;
  availableText: string; unavailableText: string; disabled: boolean;
}) {
  return (
    <div className="space-y-1 rounded-md border p-3">
      <label htmlFor={id} className="flex items-center gap-3 text-sm font-medium">
        <input id={id} type="checkbox" className="h-4 w-4" checked={checked} disabled={disabled}
          onChange={(e) => onChange(e.target.checked)} aria-describedby={`${id}-state`} />
        {label}
      </label>
      <p id={`${id}-state`} className={`flex items-start gap-1.5 text-xs ${available ? 'text-green-700' : 'text-amber-700'}`}>
        {available ? <CheckCircle2 size={14} aria-hidden className="mt-0.5 shrink-0" /> : <AlertTriangle size={14} aria-hidden className="mt-0.5 shrink-0" />}
        <span>{available ? availableText : unavailableText}</span>
      </p>
    </div>
  );
}

/**
 * The caller's own external channel preferences. Availability flags come from the API
 * (emailAvailable / smsAvailable); when a provider is not configured the form says so plainly and
 * does not pretend that messages will be delivered.
 */
export function NotificationPreferencesForm() {
  const { t } = useModuleT(workflowDict);
  const prefs = useLoad(async () => (await notificationPrefsApi.get()).data, 'prefs');
  const [email, setEmail] = useState(false);
  const [sms, setSms] = useState(false);
  const [saved, setSaved] = useState<NotificationPreferences | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');

  useEffect(() => {
    if (prefs.data) { setEmail(prefs.data.emailEnabled); setSms(prefs.data.smsEnabled); setSaved(prefs.data); }
  }, [prefs.data]);

  if (prefs.loading) return <LoadingState rows={3} />;
  if (prefs.error || !saved) return <ErrorState message={describeError(prefs.error, t)} onRetry={prefs.reload} />;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true); setError(''); setOk('');
    try {
      // types is sent back unchanged (null = all types)
      const r = await notificationPrefsApi.put({ emailEnabled: email, smsEnabled: sms, types: saved.types ?? null });
      setSaved(r.data); setEmail(r.data.emailEnabled); setSms(r.data.smsEnabled);
      setOk(t('prefsSaved'));
    } catch (err) {
      setError(describeError(err, t)); // 422 explains a missing email address or phone number
    } finally {
      setBusy(false);
    }
  };

  const dirty = email !== saved.emailEnabled || sms !== saved.smsEnabled;

  return (
    <Card>
      <CardHeader><CardTitle className="text-base">{t('prefsTitle')}</CardTitle></CardHeader>
      <CardContent>
        <p className="mb-4 text-sm text-muted-foreground">{t('prefsSubtitle')}</p>
        <form onSubmit={submit} className="space-y-3">
          <p className="rounded-md bg-muted/50 px-3 py-2 text-sm">{t('inAppAlways')}</p>
          <ChannelRow id="pref-email" label={t('channelOn', { c: t('channelEmail') })} checked={email} onChange={setEmail}
            available={saved.emailAvailable} availableText={t('availableEmail')} unavailableText={t('notAvailableEmail')} disabled={busy} />
          <ChannelRow id="pref-sms" label={t('channelOn', { c: t('channelSms') })} checked={sms} onChange={setSms}
            available={saved.smsAvailable} availableText={t('availableSms')} unavailableText={t('notAvailableSms')} disabled={busy} />
          <p className="text-xs text-muted-foreground">{t('prefsHonest')}</p>
          {error && <p role="alert" className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">{error}</p>}
          {ok && <p role="status" className="text-sm text-green-700">{ok}</p>}
          <Button type="submit" disabled={busy || !dirty}>{busy ? t('working') : t('savePrefs')}</Button>
        </form>
      </CardContent>
    </Card>
  );
}
