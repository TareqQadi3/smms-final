'use client';

import { cloneElement, isValidElement, useId, type ReactElement, type ReactNode } from 'react';
import { Label } from '@/components/ui/label';
import { useModuleT } from '@/lib/module-i18n';
import { cn } from '@/lib/utils';
import { commonDict } from './i18n';

interface ControlProps {
  id?: string;
  'aria-describedby'?: string;
  'aria-invalid'?: boolean | 'true' | 'false';
  'aria-required'?: boolean;
}

/**
 * Label + control + hint + error with accessible wiring. The single child must be one form control
 * element (Input, Textarea, a native select...); FormField injects `id`, `aria-describedby`,
 * `aria-invalid` and `aria-required`. The error uses role="alert".
 */
export function FormField({
  label,
  error,
  hint,
  required,
  id,
  className,
  children,
}: {
  label: ReactNode;
  error?: string;
  hint?: ReactNode;
  required?: boolean;
  id?: string;
  className?: string;
  children: ReactElement<ControlProps>;
}) {
  const { t } = useModuleT(commonDict);
  const autoId = useId();
  const fieldId = id ?? children.props.id ?? `field-${autoId}`;
  const hintId = hint ? `${fieldId}-hint` : undefined;
  const errorId = error ? `${fieldId}-error` : undefined;
  const describedBy = [children.props['aria-describedby'], hintId, errorId].filter(Boolean).join(' ') || undefined;

  const control = isValidElement(children)
    ? cloneElement(children, {
        id: fieldId,
        'aria-describedby': describedBy,
        'aria-invalid': error ? true : children.props['aria-invalid'],
        'aria-required': required ? true : children.props['aria-required'],
      })
    : children;

  return (
    <div className={cn('space-y-1.5', className)}>
      <Label htmlFor={fieldId}>
        {label}
        {required && (
          <span aria-hidden="true" className="text-destructive">
            *
          </span>
        )}
        {required && <span className="sr-only">({t('required')})</span>}
      </Label>
      {control}
      {hint && (
        <p id={hintId} className="text-xs text-muted-foreground">
          {hint}
        </p>
      )}
      {error && (
        <p id={errorId} role="alert" className="text-xs text-destructive">
          {error}
        </p>
      )}
    </div>
  );
}
