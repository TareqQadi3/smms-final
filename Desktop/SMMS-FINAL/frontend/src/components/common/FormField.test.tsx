import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { FormField } from './FormField';
import { Input } from '@/components/ui/input';

describe('FormField', () => {
  it('associates the label with the control', () => {
    render(
      <FormField label="اسم المستخدم">
        <Input />
      </FormField>
    );
    const input = screen.getByLabelText('اسم المستخدم');
    expect(input).toBeInTheDocument();
    expect(input).not.toHaveAttribute('aria-invalid');
  });

  it('wires hint and error through aria-describedby and marks invalid', () => {
    render(
      <FormField label="Name" hint="Min 3 chars" error="Too short" required>
        <Input />
      </FormField>
    );
    const input = screen.getByRole('textbox', { name: /Name/ });
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAttribute('aria-required', 'true');
    expect(input).toHaveAccessibleDescription('Min 3 chars Too short');
    expect(screen.getByRole('alert')).toHaveTextContent('Too short');
  });

  it('keeps an explicit id and existing describedby', () => {
    render(
      <FormField label="Qty" id="qty">
        <Input aria-describedby="extra" />
      </FormField>
    );
    expect(screen.getByLabelText('Qty')).toHaveAttribute('id', 'qty');
    expect(screen.getByLabelText('Qty')).toHaveAttribute('aria-describedby', 'extra');
  });
});
