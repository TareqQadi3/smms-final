import { describe, expect, it, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { EmptyState, ErrorState, LoadingState, PermissionDenied } from '.';
import { useAuthStore } from '@/store/authStore';

describe('LoadingState', () => {
  it('is a busy status with a localized label', () => {
    render(<LoadingState rows={2} />);
    const el = screen.getByRole('status');
    expect(el).toHaveAttribute('aria-busy', 'true');
    expect(el).toHaveTextContent('جاري التحميل...');
  });
  it('renders English', () => {
    useAuthStore.setState({ lang: 'en' });
    render(<LoadingState />);
    expect(screen.getByRole('status')).toHaveTextContent('Loading...');
  });
});

describe('EmptyState', () => {
  it('shows default Arabic title, description and action', () => {
    render(<EmptyState description="أضف عنصرا" action={<button>إضافة</button>} />);
    expect(screen.getByRole('status')).toHaveTextContent('لا توجد بيانات');
    expect(screen.getByText('أضف عنصرا')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'إضافة' })).toBeInTheDocument();
  });
  it('accepts a custom title and English default', () => {
    useAuthStore.setState({ lang: 'en' });
    const { rerender } = render(<EmptyState />);
    expect(screen.getByText('No data')).toBeInTheDocument();
    rerender(<EmptyState title="Nothing here" />);
    expect(screen.getByText('Nothing here')).toBeInTheDocument();
  });
});

describe('ErrorState', () => {
  it('is an alert and retry calls back', async () => {
    const onRetry = vi.fn();
    render(<ErrorState message="تعذر الاتصال" onRetry={onRetry} />);
    expect(screen.getByRole('alert')).toHaveTextContent('تعذر الاتصال');
    await userEvent.click(screen.getByRole('button', { name: 'إعادة المحاولة' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });
  it('hides retry without a handler and localizes to English', () => {
    render(<ErrorState />);
    expect(screen.queryByRole('button')).toBeNull();
    act(() => useAuthStore.setState({ lang: 'en' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Something went wrong');
  });
});

describe('PermissionDenied', () => {
  it('renders Arabic then English defaults', () => {
    render(<PermissionDenied />);
    expect(screen.getByRole('alert')).toHaveTextContent('غير مصرح');
    act(() => useAuthStore.setState({ lang: 'en' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Access denied');
  });
});
