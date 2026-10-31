'use client';

import Link from 'next/link';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useModuleT } from '@/lib/module-i18n';
import { formatDate } from '@/lib/utils';
import { inventoryDict } from '../i18n';
import { fmtQty, localName } from '../shared';
import { ADJUSTMENT_REASONS, type AdjustmentReason, type Movement, type MovementKind } from '../types';

const INBOUND: MovementKind[] = ['opening', 'receipt', 'return_in', 'adjustment_in', 'reversal_in'];

/** A reversal cannot be reversed, an already reversed movement cannot be reversed again. */
export const isReversible = (m: Movement) =>
  !m.reversedById && !m.reversalOfId && m.kind !== 'reversal_in' && m.kind !== 'reversal_out' && m.kind !== 'opening';

export function MovementsTable({
  rows,
  onReverse,
  showItem = true,
}: {
  rows: Movement[];
  /** Provided only for roles that may reverse; the server enforces it anyway. */
  onReverse?: (m: Movement) => void;
  showItem?: boolean;
}) {
  const { t, isAr, lang } = useModuleT(inventoryDict);
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>{t('date')}</TableHead>
          <TableHead>{t('kind')}</TableHead>
          {showItem && <TableHead>{t('item')}</TableHead>}
          <TableHead>{t('warehouse')}</TableHead>
          <TableHead>{t('quantity')}</TableHead>
          <TableHead>{t('balanceAfter')}</TableHead>
          <TableHead>{t('references')}</TableHead>
          <TableHead>{t('reasonNote')}</TableHead>
          <TableHead>{t('actor')}</TableHead>
          {onReverse && <TableHead>{t('actions')}</TableHead>}
        </TableRow>
      </TableHeader>
      <TableBody>
        {rows.map((m) => {
          const inbound = INBOUND.includes(m.kind);
          return (
            <TableRow key={m.id}>
              <TableCell>{formatDate(m.createdAt, lang, true)}</TableCell>
              <TableCell>
                <Badge variant={inbound ? 'secondary' : 'outline'}>{t(`kind_${m.kind}`)}</Badge>
                {m.reversedById && <Badge variant="destructive" className="ms-1">{t('reversed')}</Badge>}
              </TableCell>
              {showItem && (
                <TableCell>
                  <Link href={`/inventory/${m.item.id}`} className="text-primary hover:underline">
                    {localName(m.item, isAr)}
                  </Link>
                </TableCell>
              )}
              <TableCell>{localName(m.warehouse, isAr)}</TableCell>
              <TableCell dir="ltr" className="text-start font-mono">
                {inbound ? '+' : '-'}
                {fmtQty(m.quantity)}
              </TableCell>
              <TableCell dir="ltr" className="text-start font-mono">{fmtQty(m.balanceAfter)}</TableCell>
              <TableCell className="space-x-2 space-x-reverse text-xs">
                {m.workOrderId && (
                  <Link className="text-primary hover:underline" href={`/work-orders/${m.workOrderId}`}>
                    {m.ticketNumber ?? t('workOrder')}
                  </Link>
                )}
                {m.materialRequestId && (
                  <Link className="text-primary hover:underline" href={`/material-requests/${m.materialRequestId}`}>
                    {t('materialRequest')}
                  </Link>
                )}
                {m.poId && (
                  <Link className="text-primary hover:underline" href={`/procurement/purchase-orders/${m.poId}`}>
                    {t('purchaseOrder')}
                  </Link>
                )}
                {m.reference && <span>{m.reference}</span>}
              </TableCell>
              <TableCell className="max-w-[260px] whitespace-normal text-xs">
                {m.reasonCode && <span className="font-medium">{(ADJUSTMENT_REASONS as string[]).includes(m.reasonCode) ? t(`reason_${m.reasonCode as AdjustmentReason}`) : m.reasonCode}: </span>}
                {m.note ?? ''}
              </TableCell>
              <TableCell>{localName(m.actor, isAr)}</TableCell>
              {onReverse && (
                <TableCell>
                  {isReversible(m) && (
                    <Button size="sm" variant="outline" onClick={() => onReverse(m)}>
                      {t('reverse')}
                    </Button>
                  )}
                </TableCell>
              )}
            </TableRow>
          );
        })}
      </TableBody>
    </Table>
  );
}
