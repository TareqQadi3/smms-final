import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AdjustmentForm } from './AdjustmentForm';
import { inventoryApi } from '../api';
import { httpError, page, signIn } from '../testUtils';

vi.mock('../api', () => ({
  inventoryApi: { listWarehouses: vi.fn(), listItems: vi.fn(), adjust: vi.fn() },
}));
const api = vi.mocked(inventoryApi);

const wh = { id: 'w-1', centerId: 'c-1', code: 'WH1', name: 'Main', nameAr: 'الرئيسي', active: true };
const item = { id: 'i-1', sku: 'SKU-1', name: 'Filter', active: true };

async function fillAll(note: string) {
  await userEvent.selectOptions(await screen.findByLabelText(/Warehouse/), 'w-1');
  await userEvent.selectOptions(await screen.findByLabelText(/^Item/), 'i-1');
  await userEvent.selectOptions(screen.getByLabelText(/Direction/), 'out');
  await userEvent.type(screen.getByLabelText(/Quantity/), '2');
  await userEvent.selectOptions(screen.getByLabelText(/Reason type/), 'damage');
  if (note) await userEvent.type(screen.getByRole('textbox', { name: /^Reason/ }), note);
}

describe('AdjustmentForm', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    signIn('maintenance_manager');
    api.listWarehouses.mockResolvedValue(page([wh]) as never);
    api.listItems.mockResolvedValue(page([item]) as never);
  });

  it('requires a reason of at least 5 characters', async () => {
    render(<AdjustmentForm />);
    await fillAll('abc');
    await userEvent.click(screen.getByRole('button', { name: 'Post adjustment' }));
    expect(await screen.findByText('Write a reason of at least 5 characters')).toBeInTheDocument();
    expect(api.adjust).not.toHaveBeenCalled();
  });

  it('posts a valid adjustment with an Idempotency-Key', async () => {
    api.adjust.mockResolvedValue({ id: 'm', quantity: 2, balanceAfter: 3 } as never);
    render(<AdjustmentForm />);
    await fillAll('broken in transit');
    await userEvent.click(screen.getByRole('button', { name: 'Post adjustment' }));
    await screen.findByText(/Adjustment of 2 posted. New balance: 3/);
    expect(api.adjust.mock.calls[0][0]).toMatchObject({ direction: 'out', reasonCode: 'damage', quantity: 2, note: 'broken in transit' });
    expect(api.adjust.mock.calls[0][1]).toMatch(/^[A-Za-z0-9-]{8,64}$/);
  });

  it('surfaces a 409 insufficient stock message', async () => {
    api.adjust.mockRejectedValue(httpError(409, 'Insufficient stock for SKU-1: available 1'));
    render(<AdjustmentForm />);
    await fillAll('broken in transit');
    await userEvent.click(screen.getByRole('button', { name: 'Post adjustment' }));
    expect(await screen.findByText(/Insufficient stock for SKU-1: available 1/)).toBeInTheDocument();
  });

  it('the warehouse role may not adjust', () => {
    signIn('warehouse');
    render(<AdjustmentForm />);
    expect(screen.getByRole('alert')).toHaveTextContent('Access denied');
  });
});
