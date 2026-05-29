'use client';
import { useEffect, useState } from 'react';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { useTranslate } from '@/hooks/useTranslate';

interface Props {
  labelAr:       string;
  labelEn:       string;
  valueAr:       string;
  valueEn:       string;
  onChangeAr:    (v: string) => void;
  onChangeEn:    (v: string) => void;
  placeholderAr?: string;
  placeholderEn?: string;
  required?:     boolean;
  disabled?:     boolean;
}

/**
 * Bilingual input pair with auto-translation.
 * – Typing in Arabic  → auto-fills English after 700ms
 * – Typing in English → auto-fills Arabic  after 700ms
 * – User can always override either field manually
 */
export function BilingualInput({
  labelAr, labelEn,
  valueAr, valueEn,
  onChangeAr, onChangeEn,
  placeholderAr, placeholderEn,
  required, disabled,
}: Props) {
  const [activeSource, setActiveSource] = useState<'ar' | 'en' | null>(null);
  const [arInput, setArInput] = useState(valueAr);
  const [enInput, setEnInput] = useState(valueEn);

  // sync external value changes (e.g. reset form)
  useEffect(() => { setArInput(valueAr); }, [valueAr]);
  useEffect(() => { setEnInput(valueEn); }, [valueEn]);

  const { translated: arFromEn, translating: trAr } = useTranslate(
    activeSource === 'en' ? enInput : '', 'en'
  );
  const { translated: enFromAr, translating: trEn } = useTranslate(
    activeSource === 'ar' ? arInput : '', 'ar'
  );

  // Apply translated result to the opposite field (only if user didn't type there)
  useEffect(() => {
    if (activeSource === 'ar' && enFromAr) {
      setEnInput(enFromAr);
      onChangeEn(enFromAr);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enFromAr]);

  useEffect(() => {
    if (activeSource === 'en' && arFromEn) {
      setArInput(arFromEn);
      onChangeAr(arFromEn);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [arFromEn]);

  return (
    <div className="grid grid-cols-2 gap-3">
      {/* Arabic */}
      <div className="space-y-1.5">
        <Label className="flex items-center gap-1.5">
          {labelAr}
          {required && <span className="text-destructive">*</span>}
        </Label>
        <div className="relative">
          <Input
            dir="rtl"
            required={required}
            disabled={disabled}
            value={arInput}
            placeholder={placeholderAr}
            onChange={e => {
              setArInput(e.target.value);
              onChangeAr(e.target.value);
              setActiveSource('ar');
            }}
            className={trEn && activeSource === 'ar' ? 'border-green-400 ring-1 ring-green-300' : ''}
          />
          {trEn && activeSource === 'ar' && (
            <span className="absolute top-1/2 left-2 -translate-y-1/2 text-[9px] text-green-600 font-bold animate-pulse">⟳</span>
          )}
        </div>
      </div>

      {/* English */}
      <div className="space-y-1.5">
        <Label className="flex items-center gap-1.5">
          {labelEn}
          <span className="text-[10px] bg-green-100 text-green-700 px-1.5 py-0.5 rounded font-semibold">AUTO</span>
        </Label>
        <div className="relative">
          <Input
            dir="ltr"
            disabled={disabled}
            value={enInput}
            placeholder={placeholderEn}
            onChange={e => {
              setEnInput(e.target.value);
              onChangeEn(e.target.value);
              setActiveSource('en');
            }}
            className={trAr && activeSource === 'en' ? 'border-green-400 ring-1 ring-green-300' : ''}
          />
          {trAr && activeSource === 'en' && (
            <span className="absolute top-1/2 right-2 -translate-y-1/2 text-[9px] text-green-600 font-bold animate-pulse">⟳</span>
          )}
        </div>
      </div>
    </div>
  );
}
