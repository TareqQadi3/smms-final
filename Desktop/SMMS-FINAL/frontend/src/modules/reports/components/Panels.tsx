'use client';

import { useCallback, useMemo, useState } from 'react';
import { SELECT_CLASS } from '@/components/assets/styles';
import { FormField, Pagination } from '@/components/common';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useModuleT } from '@/lib/module-i18n';
import { formatDate } from '@/lib/utils';
import { enumDict, enumLabel } from '@/modules/assets/enums';
import { useLoad } from '@/modules/assets/lib';
import { REPORT_PATHS, centerParams, reportsApi, snapshotParams, workOrderParams } from '../api';
import { reportsDict, type ReportKey } from '../i18n';
import type { InventoryReportId, ReportFilters } from '../types';
import { BarChart, CsvButton, ReportState, Stat, fmt } from './Widgets';

interface PanelProps { filters: ReportFilters }

function Th({ children }: { children: React.ReactNode }) {
  return <TableHead scope="col">{children}</TableHead>;
}

/** Plain scrollable table wrapper with a screen-reader caption. */
function Grid({ caption, head, children }: { caption: string; head: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="overflow-x-auto rounded-lg border">
      <Table>
        <caption className="sr-only">{caption}</caption>
        <TableHeader><TableRow>{head}</TableRow></TableHeader>
        <TableBody>{children}</TableBody>
      </Table>
    </div>
  );
}

function useLabels() {
  const { t } = useModuleT(reportsDict);
  const { t: te } = useModuleT(enumDict);
  return { t, te };
}

// ── Volume ───────────────────────────────────────────────
const VOLUME_GROUPS = ['status', 'priority', 'subType', 'controlRoom', 'day', 'week'] as const;
const VOLUME_LABEL: Record<(typeof VOLUME_GROUPS)[number], ReportKey> = {
  status: 'gStatus', priority: 'gPriority', subType: 'gSubType', controlRoom: 'gControlRoom', day: 'gDay', week: 'gWeek',
};

export function VolumePanel({ filters }: PanelProps) {
  const { t, te } = useLabels();
  const [groupBy, setGroupBy] = useState<(typeof VOLUME_GROUPS)[number]>('status');
  const params = useMemo(() => workOrderParams(filters, { groupBy }), [filters, groupBy]);
  const fetcher = useCallback(() => reportsApi.volume(params), [params]);
  const state = useLoad(fetcher);
  const labelOf = (key: string, fallback: string) =>
    groupBy === 'status' ? enumLabel(te, 'woStatus', key) : groupBy === 'priority' ? enumLabel(te, 'priority', key)
      : groupBy === 'subType' ? enumLabel(te, 'subType', key) : fallback || key;
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <FormField label={t('groupBy')}>
          <select className={SELECT_CLASS} value={groupBy} onChange={(e) => setGroupBy(e.target.value as typeof groupBy)}>
            {VOLUME_GROUPS.map((g) => <option key={g} value={g}>{t(VOLUME_LABEL[g])}</option>)}
          </select>
        </FormField>
        <CsvButton path={REPORT_PATHS.volume} params={params} fallbackName="work-order-volume.csv" />
      </div>
      <ReportState state={state} isEmpty={(d) => d.groups.length === 0}>
        {(d) => (
          <div className="space-y-4">
            <Stat label={t('total')} value={fmt(d.total, 0)} />
            <BarChart title={t('chartVolume', { by: t(VOLUME_LABEL[groupBy]) })} items={d.groups.map((g) => ({ label: labelOf(g.key, g.label), value: g.count }))} />
            <Grid caption={t('tabVolume')} head={<><Th>{t('group')}</Th><Th>{t('count')}</Th></>}>
              {d.groups.map((g) => (
                <TableRow key={g.key}><TableCell>{labelOf(g.key, g.label)}</TableCell><TableCell className="tabular-nums">{fmt(g.count, 0)}</TableCell></TableRow>
              ))}
            </Grid>
          </div>
        )}
      </ReportState>
    </div>
  );
}

// ── Turnaround ───────────────────────────────────────────
export function TurnaroundPanel({ filters }: PanelProps) {
  const { t, te } = useLabels();
  const [groupBy, setGroupBy] = useState<'priority' | 'subType'>('priority');
  const params = useMemo(() => workOrderParams(filters, { groupBy }), [filters, groupBy]);
  const fetcher = useCallback(() => reportsApi.turnaround(params), [params]);
  const state = useLoad(fetcher);
  const labelOf = (k: string) => enumLabel(te, groupBy, k);
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <FormField label={t('groupBy')}>
          <select className={SELECT_CLASS} value={groupBy} onChange={(e) => setGroupBy(e.target.value as typeof groupBy)}>
            <option value="priority">{t('gPriority')}</option>
            <option value="subType">{t('gSubType')}</option>
          </select>
        </FormField>
        <CsvButton path={REPORT_PATHS.turnaround} params={params} fallbackName="work-order-turnaround.csv" />
      </div>
      <p className="text-xs text-muted-foreground">{t('turnaroundNote')}</p>
      <ReportState state={state} isEmpty={(d) => d.length === 0}>
        {(rows) => (
          <div className="space-y-4">
            <BarChart title={t('chartTurnaround')} items={rows.map((r) => ({ label: labelOf(r.key), value: Number(r.avgHours ?? 0), display: fmt(r.avgHours) }))} />
            <Grid caption={t('tabTurnaround')} head={<><Th>{t('group')}</Th><Th>{t('completedCount')}</Th><Th>{t('avgHours')}</Th><Th>{t('p50')}</Th><Th>{t('p90')}</Th></>}>
              {rows.map((r) => (
                <TableRow key={r.key}>
                  <TableCell>{labelOf(r.key)}</TableCell>
                  <TableCell className="tabular-nums">{fmt(r.completedCount, 0)}</TableCell>
                  <TableCell className="tabular-nums">{fmt(r.avgHours)}</TableCell>
                  <TableCell className="tabular-nums">{fmt(r.p50Hours)}</TableCell>
                  <TableCell className="tabular-nums">{fmt(r.p90Hours)}</TableCell>
                </TableRow>
              ))}
            </Grid>
          </div>
        )}
      </ReportState>
    </div>
  );
}

// ── SLA ──────────────────────────────────────────────────
export function SlaPanel({ filters }: PanelProps) {
  const { t, te } = useLabels();
  const params = useMemo(() => workOrderParams(filters), [filters]);
  const fetcher = useCallback(() => reportsApi.sla(params), [params]);
  const state = useLoad(fetcher);
  return (
    <div className="space-y-4">
      <div className="flex justify-end"><CsvButton path={REPORT_PATHS.sla} params={params} fallbackName="sla.csv" /></div>
      <ReportState state={state} isEmpty={(d) => d.length === 0}>
        {(rows) => (
          <div className="space-y-4">
            <BarChart title={t('chartSla')} max={100} items={rows.map((r) => ({ label: enumLabel(te, 'priority', r.priority), value: Number(r.compliancePct ?? 0), display: r.compliancePct === null ? '-' : `${fmt(r.compliancePct)}%` }))} />
            <Grid caption={t('tabSla')} head={<><Th>{t('priority')}</Th><Th>{t('due')}</Th><Th>{t('met')}</Th><Th>{t('breached')}</Th><Th>{t('compliance')}</Th><Th>{t('avgPaused')}</Th><Th>{t('openBreached')}</Th></>}>
              {rows.map((r) => (
                <TableRow key={r.priority}>
                  <TableCell>{enumLabel(te, 'priority', r.priority)}</TableCell>
                  <TableCell className="tabular-nums">{fmt(r.due, 0)}</TableCell>
                  <TableCell className="tabular-nums">{fmt(r.metOnTime, 0)}</TableCell>
                  <TableCell className="tabular-nums">{fmt(r.breached, 0)}</TableCell>
                  <TableCell className="tabular-nums">{r.compliancePct === null ? '-' : `${fmt(r.compliancePct)}%`}</TableCell>
                  <TableCell className="tabular-nums">{fmt(r.avgPausedHours)}</TableCell>
                  <TableCell className="tabular-nums">{fmt(r.openBreached, 0)}</TableCell>
                </TableRow>
              ))}
            </Grid>
          </div>
        )}
      </ReportState>
    </div>
  );
}

// ── Escalations ──────────────────────────────────────────
export function EscalationsPanel({ filters }: PanelProps) {
  const { t } = useLabels();
  const params = useMemo(() => workOrderParams(filters), [filters]);
  const fetcher = useCallback(() => reportsApi.escalations(params), [params]);
  const state = useLoad(fetcher);
  return (
    <div className="space-y-4">
      <ReportState state={state}>
        {(d) =>
          !d.available ? (
            <p role="status" className="rounded-lg border p-4 text-sm text-muted-foreground">{t('escUnavailable')}</p>
          ) : (
            <div className="space-y-4">
              <div className="flex justify-end"><CsvButton path={REPORT_PATHS.escalations} params={params} fallbackName="escalations.csv" /></div>
              {d.groups.length === 0 ? (
                <p role="status" className="text-sm text-muted-foreground">{t('noData')}</p>
              ) : (
                <>
                  <BarChart
                    title={t('chartEsc')}
                    items={Object.entries(d.groups.reduce<Record<number, number>>((a, g) => ({ ...a, [g.level]: (a[g.level] ?? 0) + g.count }), {})).map(([lvl, c]) => ({ label: t('levelN', { n: lvl }), value: c }))}
                  />
                  <Grid caption={t('tabEscalations')} head={<><Th>{t('level')}</Th><Th>{t('trigger')}</Th><Th>{t('count')}</Th></>}>
                    {d.groups.map((g, i) => (
                      <TableRow key={`${g.level}-${g.trigger}-${i}`}>
                        <TableCell>{t('levelN', { n: g.level })}</TableCell>
                        <TableCell>{g.trigger ?? '-'}</TableCell>
                        <TableCell className="tabular-nums">{fmt(g.count, 0)}</TableCell>
                      </TableRow>
                    ))}
                  </Grid>
                  {d.byDay.length > 0 && (
                    <BarChart title={t('byDay')} items={d.byDay.map((x) => ({ label: x.day, value: x.count }))} />
                  )}
                </>
              )}
            </div>
          )
        }
      </ReportState>
    </div>
  );
}

// ── Technicians (name and counts only) ───────────────────
export function TechniciansPanel({ filters }: PanelProps) {
  const { t } = useLabels();
  const [page, setPage] = useState(0);
  const params = useMemo(() => workOrderParams(filters, { page, size: 20 }), [filters, page]);
  const fetcher = useCallback(() => reportsApi.technicians(params), [params]);
  const state = useLoad(fetcher);
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-xs text-muted-foreground">{t('techNote')}</p>
        <CsvButton path={REPORT_PATHS.technicians} params={workOrderParams(filters)} fallbackName="technicians.csv" />
      </div>
      <ReportState state={state} isEmpty={(d) => d.content.length === 0}>
        {(d) => (
          <>
            <Grid
              caption={t('tabTechnicians')}
              head={<><Th>{t('technician')}</Th><Th>{t('assigned')}</Th><Th>{t('completed')}</Th><Th>{t('open')}</Th><Th>{t('reopened')}</Th><Th>{t('avgTurnaround')}</Th><Th>{t('byPriority')}</Th><Th>{t('emergency')}</Th></>}
            >
              {d.content.map((r) => (
                <TableRow key={r.technician.id}>
                  <TableCell>{r.technician.fullName ?? '-'}</TableCell>
                  <TableCell className="tabular-nums">{fmt(r.assigned, 0)}</TableCell>
                  <TableCell className="tabular-nums">{fmt(r.completed, 0)}</TableCell>
                  <TableCell className="tabular-nums">{fmt(r.open, 0)}</TableCell>
                  <TableCell className="tabular-nums">{fmt(r.reopened, 0)}</TableCell>
                  <TableCell className="tabular-nums">{fmt(r.avgTurnaroundHours)}</TableCell>
                  <TableCell className="tabular-nums">
                    {r.completedByPriority.critical} / {r.completedByPriority.high} / {r.completedByPriority.medium} / {r.completedByPriority.low}
                  </TableCell>
                  <TableCell className="tabular-nums">{fmt(r.emergencyHandled, 0)}</TableCell>
                </TableRow>
              ))}
            </Grid>
            <Pagination page={d.page} totalPages={d.totalPages} onPageChange={setPage} />
          </>
        )}
      </ReportState>
    </div>
  );
}

// ── Assets and PM ────────────────────────────────────────
export function AssetsPmPanel({ filters }: PanelProps) {
  const { t, te } = useLabels();
  const { lang } = useModuleT(reportsDict);
  const params = useMemo(() => centerParams(filters), [filters]);
  const f1 = useCallback(() => reportsApi.assetSummary(params), [params]);
  const f2 = useCallback(() => reportsApi.pmCompliance(params), [params]);
  const summary = useLoad(f1);
  const pm = useLoad(f2);
  const outcome = (o: string) => {
    const k = `out_${o}` as ReportKey;
    const v = t(k);
    return v === k ? o : v;
  };
  return (
    <div className="space-y-8">
      <section aria-labelledby="rep-assets" className="space-y-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 id="rep-assets" className="text-lg font-semibold">{t('assetSummary')}</h2>
          <CsvButton path={REPORT_PATHS.assetSummary} params={params} fallbackName="asset-summary.csv" />
        </div>
        <ReportState state={summary}>
          {(d) => (
            <div className="space-y-4">
              <div className="grid grid-cols-2 gap-3 md:grid-cols-3">
                <Stat label={t('totalAssets')} value={fmt(d.totalAssets, 0)} />
                <Stat label={t('decommissioned')} value={fmt(d.decommissioned, 0)} />
                <Stat label={t('downtimeHours')} value={fmt(d.downtimeHours)} />
              </div>
              <div className="grid gap-6 md:grid-cols-2">
                <BarChart title={t('chartAssetStatus')} items={d.byStatus.map((c) => ({ label: enumLabel(te, 'assetStatus', c.key), value: c.count }))} />
                <BarChart title={t('byCriticality')} items={d.byCriticality.map((c) => ({ label: enumLabel(te, 'crit', c.key), value: c.count }))} />
              </div>
              {d.byCategory.length > 0 && <BarChart title={t('byCategory')} items={d.byCategory.map((c) => ({ label: c.key || '-', value: c.count }))} />}
              {d.downtimeByAsset.length > 0 && (
                <Grid caption={t('downtimeByAsset')} head={<><Th>{t('asset')}</Th><Th>{t('periods')}</Th><Th>{t('hours')}</Th></>}>
                  {d.downtimeByAsset.map((a) => (
                    <TableRow key={a.assetId}>
                      <TableCell>{a.assetCode ? `${a.assetCode} - ` : ''}{a.assetName}</TableCell>
                      <TableCell className="tabular-nums">{fmt(a.periods, 0)}</TableCell>
                      <TableCell className="tabular-nums">{fmt(a.hours)}</TableCell>
                    </TableRow>
                  ))}
                </Grid>
              )}
            </div>
          )}
        </ReportState>
      </section>

      <section aria-labelledby="rep-pm" className="space-y-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 id="rep-pm" className="text-lg font-semibold">{t('pmCompliance')}</h2>
          <CsvButton path={REPORT_PATHS.pmCompliance} params={params} fallbackName="pm-compliance.csv" />
        </div>
        <ReportState state={pm} isEmpty={(d) => d.rows.length === 0 && d.due === 0}>
          {(d) => (
            <div className="space-y-4">
              <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
                <Stat label={t('pmDue')} value={fmt(d.due, 0)} />
                <Stat label={t('pmGenerated')} value={fmt(d.generated, 0)} />
                <Stat label={t('pmOnTime')} value={fmt(d.completedOnTime, 0)} />
                <Stat label={t('pmLate')} value={fmt(d.completedLate, 0)} />
                <Stat label={t('pmOverdue')} value={fmt(d.overdue, 0)} />
                <Stat label={t('pmOpen')} value={fmt(d.open, 0)} />
                <Stat label={t('pmFailed')} value={fmt(d.failed, 0)} />
                <Stat label={t('pmNotGenerated')} value={fmt(d.dueNotGenerated, 0)} />
              </div>
              <BarChart
                title={t('chartPm')}
                items={[
                  { label: t('pmOnTime'), value: d.completedOnTime },
                  { label: t('pmLate'), value: d.completedLate },
                  { label: t('pmOverdue'), value: d.overdue },
                  { label: t('pmOpen'), value: d.open },
                  { label: t('pmFailed'), value: d.failed },
                  { label: t('pmSkipped'), value: d.skipped },
                ]}
              />
              {d.rows.length > 0 && (
                <Grid caption={t('pmCompliance')} head={<><Th>{t('plan')}</Th><Th>{t('asset')}</Th><Th>{t('dueDate')}</Th><Th>{t('ticket')}</Th><Th>{t('outcome')}</Th></>}>
                  {d.rows.map((r, i) => (
                    <TableRow key={`${r.planId}-${r.dueDate}-${i}`}>
                      <TableCell>{r.planTitle}</TableCell>
                      <TableCell>{r.assetCode ? `${r.assetCode} - ` : ''}{r.assetName}</TableCell>
                      <TableCell>{formatDate(r.dueDate as string, lang)}</TableCell>
                      <TableCell className="font-mono text-xs">{r.ticketNumber ?? '-'}</TableCell>
                      <TableCell>{outcome(r.outcome)}</TableCell>
                    </TableRow>
                  ))}
                </Grid>
              )}
            </div>
          )}
        </ReportState>
      </section>
    </div>
  );
}

// ── Inventory and purchasing ─────────────────────────────
const INVENTORY_LABEL: Record<InventoryReportId, ReportKey> = {
  stock: 'r_stock', movements: 'r_movements', consumption: 'r_consumption', 'open-material-requests': 'r_open', purchasing: 'r_purchasing',
};

export function InventoryPanel({ filters, includePurchasing }: PanelProps & { includePurchasing: boolean }) {
  const { t } = useLabels();
  const { lang } = useModuleT(reportsDict);
  const ids: InventoryReportId[] = ['stock', 'movements', 'consumption', 'open-material-requests', ...(includePurchasing ? (['purchasing'] as const) : [])];
  const [id, setId] = useState<InventoryReportId>('stock');
  const [groupBy, setGroupBy] = useState<'workOrder' | 'item' | 'asset'>('workOrder');
  const snapshot = id === 'stock' || id === 'open-material-requests';
  const params = useMemo(() => snapshot ? snapshotParams(filters) : centerParams(filters, id === 'consumption' ? { groupBy } : {}), [filters, groupBy, id, snapshot]);
  const fetcher = useCallback(() => reportsApi.tabular(id, params), [id, params]);
  const state = useLoad(fetcher);
  const col = (c: string) => {
    const k = `col_${c}` as ReportKey;
    const v = t(k);
    return v === k ? c.replace(/_/g, ' ') : v;
  };
  const cell = (c: string, v: string | number | boolean | null) => {
    if (v === null || v === '') return '-';
    if (typeof v === 'boolean') return v ? t('yes') : t('no');
    if (typeof v === 'number') return fmt(v);
    if (c === 'created_at' && /^\d{4}-\d{2}-\d{2}T/.test(v)) return formatDate(v, lang, true);
    return v;
  };
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="flex flex-wrap gap-3">
          <FormField label={t('invReport')}>
            <select className={SELECT_CLASS} value={id} onChange={(e) => setId(e.target.value as InventoryReportId)}>
              {ids.map((i) => <option key={i} value={i}>{t(INVENTORY_LABEL[i])}</option>)}
            </select>
          </FormField>
          {id === 'consumption' && (
            <FormField label={t('invGroupBy')}>
              <select className={SELECT_CLASS} value={groupBy} onChange={(e) => setGroupBy(e.target.value as typeof groupBy)}>
                <option value="workOrder">{t('gbWorkOrder')}</option>
                <option value="item">{t('gbItem')}</option>
                <option value="asset">{t('gbAsset')}</option>
              </select>
            </FormField>
          )}
        </div>
        <CsvButton path={REPORT_PATHS[id]} params={params} fallbackName={`${id}.csv`} />
      </div>
      {snapshot && <p className="text-xs text-muted-foreground">{t('snapshotNote')}</p>}
      <ReportState state={state} isEmpty={(d) => !d.rows || d.rows.length === 0}>
        {(d) => (
          <Grid caption={t(INVENTORY_LABEL[id])} head={<>{d.columns.map((c) => <Th key={c}>{col(c)}</Th>)}</>}>
            {d.rows.map((r, i) => (
              <TableRow key={i}>
                {r.map((v, j) => <TableCell key={j} className="whitespace-normal">{cell(d.columns[j], v)}</TableCell>)}
              </TableRow>
            ))}
          </Grid>
        )}
      </ReportState>
    </div>
  );
}
