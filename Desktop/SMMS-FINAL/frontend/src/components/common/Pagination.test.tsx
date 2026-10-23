import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Pagination } from './Pagination';
import { useAuthStore } from '@/store/authStore';

describe('Pagination', () => {
  it('renders nothing for a single page', () => {
    const { container } = render(<Pagination page={0} totalPages={1} onPageChange={() => {}} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('navigates with zero-based pages and disables the edges (Arabic)', async () => {
    const onPageChange = vi.fn();
    const { rerender } = render(<Pagination page={0} totalPages={3} onPageChange={onPageChange} />);
    expect(screen.getByRole('navigation', { name: 'التنقل بين الصفحات' })).toBeInTheDocument();
    expect(screen.getByText('صفحة 1 من 3')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'السابق' })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: 'التالي' }));
    expect(onPageChange).toHaveBeenCalledWith(1);
    rerender(<Pagination page={2} totalPages={3} onPageChange={onPageChange} />);
    expect(screen.getByRole('button', { name: 'التالي' })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: 'السابق' }));
    expect(onPageChange).toHaveBeenLastCalledWith(1);
  });

  it('renders English and honours disabled', () => {
    useAuthStore.setState({ lang: 'en' });
    render(<Pagination page={1} totalPages={3} onPageChange={() => {}} disabled />);
    expect(screen.getByText('Page 2 of 3')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();
  });
});
