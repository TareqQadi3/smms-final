'use client';

import type { ReactNode } from 'react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { useModuleT } from '@/lib/module-i18n';
import { commonDict } from './i18n';

/**
 * Controlled confirmation dialog. `onConfirm` may be async; keep `open` true while it runs and pass
 * `loading` to disable both buttons. The caller closes the dialog (onOpenChange(false)) on success.
 */
export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel,
  cancelLabel,
  destructive,
  loading,
  onConfirm,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description?: ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  destructive?: boolean;
  loading?: boolean;
  onConfirm: () => void | Promise<void>;
}) {
  const { t, dir } = useModuleT(commonDict);
  return (
    <Dialog open={open} onOpenChange={(o) => !loading && onOpenChange(o)}>
      <DialogContent dir={dir} showCloseButton={false}>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          {description && <DialogDescription>{description}</DialogDescription>}
        </DialogHeader>
        <DialogFooter>
          <Button variant="outline" disabled={loading} onClick={() => onOpenChange(false)}>
            {cancelLabel ?? t('cancel')}
          </Button>
          <Button variant={destructive ? 'destructive' : 'default'} disabled={loading} onClick={() => void onConfirm()}>
            {loading ? t('working') : (confirmLabel ?? t('confirm'))}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
