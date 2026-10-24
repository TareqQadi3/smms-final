import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useAuthStore } from '@/store/authStore';
import { ConflictBanner } from './ConflictBanner';

beforeEach(() => { act(() => useAuthStore.setState({ lang: 'en' })); });

describe('ConflictBanner (optimistic locking)', () => {
  it('announces the conflict and reloads on demand', async () => {
    const onReload = vi.fn();
    render(<ConflictBanner onReload={onReload} />);
    expect(screen.getByRole('alert')).toHaveTextContent('changed by someone else');
    await userEvent.click(screen.getByRole('button', { name: 'Reload' }));
    expect(onReload).toHaveBeenCalledOnce();
  });
  it('is Arabic by default', () => {
    act(() => useAuthStore.setState({ lang: 'ar' }));
    render(<ConflictBanner onReload={() => {}} />);
    expect(screen.getByRole('alert')).toHaveTextContent('أعد تحميل الصفحة');
  });
});
