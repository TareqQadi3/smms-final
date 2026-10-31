import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ConfirmDialog } from './ConfirmDialog';
import { useAuthStore } from '@/store/authStore';

describe('ConfirmDialog', () => {
  it('renders nothing when closed', () => {
    render(<ConfirmDialog open={false} onOpenChange={() => {}} title="حذف" onConfirm={() => {}} />);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('confirms and cancels in Arabic (rtl)', async () => {
    const onConfirm = vi.fn();
    const onOpenChange = vi.fn();
    render(<ConfirmDialog open onOpenChange={onOpenChange} title="حذف العنصر" description="لا يمكن التراجع" onConfirm={onConfirm} />);
    const dialog = await screen.findByRole('dialog', { name: 'حذف العنصر' });
    expect(dialog).toHaveAttribute('dir', 'rtl');
    expect(dialog).toHaveAccessibleDescription('لا يمكن التراجع');
    await userEvent.click(screen.getByRole('button', { name: 'تأكيد' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    await userEvent.click(screen.getByRole('button', { name: 'إلغاء' }));
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it('English labels, custom confirm label and loading lock', async () => {
    useAuthStore.setState({ lang: 'en' });
    const onConfirm = vi.fn();
    const onOpenChange = vi.fn();
    const { rerender } = render(
      <ConfirmDialog open onOpenChange={onOpenChange} title="Delete" confirmLabel="Delete it" destructive onConfirm={onConfirm} />
    );
    expect(await screen.findByRole('button', { name: 'Delete it' })).toBeEnabled();
    rerender(<ConfirmDialog open onOpenChange={onOpenChange} title="Delete" destructive loading onConfirm={onConfirm} />);
    expect(screen.getByRole('button', { name: 'Working...' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled();
  });
});
