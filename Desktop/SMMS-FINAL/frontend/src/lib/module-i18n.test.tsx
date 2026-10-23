import { describe, expect, it } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import { translate, useModuleT, type ModuleDict } from '@/lib/module-i18n';
import { useAuthStore } from '@/store/authStore';

const dict: ModuleDict<'hello' | 'onlyAr' | 'count'> = {
  ar: { hello: 'مرحبا', onlyAr: 'عربي فقط', count: 'العدد {n}' },
  en: { hello: 'Hello', count: 'Count {n}' },
};

function Probe() {
  const { t, dir } = useModuleT(dict);
  return (
    <p data-dir={dir}>
      {t('hello')} | {t('onlyAr')} | {t('count', { n: 3 })}
    </p>
  );
}

describe('module-i18n', () => {
  it('defaults to Arabic', () => {
    render(<Probe />);
    expect(screen.getByText(/مرحبا/)).toHaveTextContent('مرحبا | عربي فقط | العدد 3');
    expect(screen.getByText(/مرحبا/)).toHaveAttribute('data-dir', 'rtl');
  });

  it('switches to English with Arabic fallback for missing keys', () => {
    render(<Probe />);
    act(() => useAuthStore.setState({ lang: 'en' }));
    expect(screen.getByText(/Hello/)).toHaveTextContent('Hello | عربي فقط | Count 3');
    expect(screen.getByText(/Hello/)).toHaveAttribute('data-dir', 'ltr');
  });

  it('returns the key when nothing matches and keeps unknown placeholders', () => {
    expect(translate(dict, 'en', 'nope' as 'hello')).toBe('nope');
    expect(translate(dict, 'en', 'count', {})).toBe('Count {n}');
  });
});
