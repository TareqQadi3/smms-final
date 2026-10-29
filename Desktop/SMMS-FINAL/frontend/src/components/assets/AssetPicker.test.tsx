import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '@/lib/api';
import { ASSET, page, routeGet, signIn } from '@/modules/assets/test-helpers';
import type { AssetOption } from '@/modules/assets/types';
import { AssetPicker } from './AssetPicker';

vi.mock('@/lib/api', async () => (await import('@/modules/assets/test-mock')).apiMockFactory());

function Harness({ onChange }: { onChange: (a: AssetOption | null) => void }) {
  const [v, setV] = useState<AssetOption | null>(null);
  return <AssetPicker value={v} debounceMs={0} centerId="c1" required onChange={(a) => { setV(a); onChange(a); }} />;
}

beforeEach(() => {
  vi.clearAllMocks();
  signIn('control_room');
  routeGet({
    '/assets': () => page([ASSET, { ...ASSET, id: 'a2', assetCode: 'AST-002', name: 'Pump', nameAr: 'مضخة' }, { ...ASSET, id: 'a3', name: 'Old', decommissionedAt: '2025-01-01T00:00:00Z' }]),
  });
});

describe('AssetPicker', () => {
  it('searches within the center, never offers decommissioned assets and selects with a click', async () => {
    const onChange = vi.fn();
    render(<Harness onChange={onChange} />);
    await userEvent.type(screen.getByRole('combobox'), 'pu');
    const opts = await screen.findAllByRole('option');
    expect(opts).toHaveLength(2);
    const call = vi.mocked(api.get).mock.calls.filter((c) => c[0] === '/assets').pop()!;
    expect((call[1] as { params: Record<string, unknown> }).params).toMatchObject({ q: 'pu', centerId: 'c1', size: 20 });
    await userEvent.click(opts[1]);
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ id: 'a2', assetCode: 'AST-002' }));
    expect(screen.getByTestId('asset-picker-selected')).toHaveTextContent('AST-002');
    await userEvent.click(screen.getByRole('button', { name: 'إزالة الأصل المحدد' }));
    expect(onChange).toHaveBeenLastCalledWith(null);
  });

  it('supports the keyboard (ArrowDown + Enter, Escape closes)', async () => {
    const onChange = vi.fn();
    render(<Harness onChange={onChange} />);
    const input = screen.getByRole('combobox');
    await userEvent.click(input);
    await screen.findAllByRole('option');
    await userEvent.keyboard('{ArrowDown}{Enter}');
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ id: 'a1' }));
    await userEvent.type(input, 'x');
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('marks the field required and shows the error', () => {
    render(<AssetPicker value={null} onChange={() => {}} error="مطلوب الأصل" required />);
    expect(screen.getByRole('combobox')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByRole('alert')).toHaveTextContent('مطلوب الأصل');
  });
});
