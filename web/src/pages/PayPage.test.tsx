import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../api/errors';
import { renderPage } from '../test/render';

const transfer = vi.fn();
vi.mock('../api/endpoints', () => ({
  walletApi: { wallet: vi.fn(async () => ({ balance: { amount: '1000.00', currency: 'INR' }, status: 'ACTIVE', limits: { dailyDebitLimit: '10000', dailyDebitUsed: '0' } })) },
  userApi: { beneficiaries: vi.fn(async () => []) },
  paymentApi: { resolveVpa: vi.fn(async (vpa: string) => ({ vpa, payeeName: 'Ravi Kumar' })), decodeQr: vi.fn(), decodeQrImage: vi.fn() },
  transactionApi: { transfer: (...args: unknown[]) => transfer(...args), transferStatus: vi.fn() },
}));

const { default: PayPage } = await import('./PayPage');

const success = {
  status: 201,
  data: { transactionId: 't-1', status: 'SUCCESS', amount: { amount: '250.00', currency: 'INR' }, payee: { type: 'VPA', ref: 'ravi@payverse', name: 'Ravi Kumar' },
          note: 'Dinner', failureCode: null, failureReason: null, source: 'DIRECT', createdAt: '2026-10-17T10:00:00Z', completedAt: '2026-10-17T10:00:01Z' },
};

describe('PayPage', () => {
  beforeEach(() => transfer.mockReset());

  it('pays a UPI ID after confirming with the PIN', async () => {
    transfer.mockResolvedValueOnce(success);
    const user = userEvent.setup();
    renderPage(<PayPage />, { route: '/pay', path: '/pay' });

    await user.type(screen.getByLabelText('UPI ID'), 'ravi@payverse');
    expect(await screen.findByText(/Ravi Kumar/)).toBeInTheDocument();
    await user.type(screen.getByLabelText('Amount'), '250');
    await user.type(screen.getByLabelText(/Note/), 'Dinner');
    await user.click(screen.getByRole('button', { name: /Pay ₹250.00/ }));

    const pin = await screen.findByLabelText('Transaction PIN');
    await user.type(pin, '4826');
    await user.click(screen.getByRole('button', { name: 'Confirm' }));

    expect(await screen.findByText('Payment successful')).toBeInTheDocument();
    expect(transfer).toHaveBeenCalledWith({ type: 'VPA', value: 'ravi@payverse' }, '250', 'Dinner', '4826', expect.any(String));
  });

  it('shows a friendly error and retries with the same idempotency key', async () => {
    transfer.mockRejectedValueOnce(new ApiError(503, { code: 'DOWNSTREAM_UNAVAILABLE', title: 'Service unavailable' })).mockResolvedValueOnce(success);
    const user = userEvent.setup();
    renderPage(<PayPage />, { route: '/pay', path: '/pay' });
    await user.type(screen.getByLabelText('UPI ID'), 'ravi@payverse');
    await user.type(screen.getByLabelText('Amount'), '250');

    const payOnce = async () => {
      await user.click(screen.getByRole('button', { name: /Pay ₹250.00/ }));
      await user.type(await screen.findByLabelText('Transaction PIN'), '4826');
      await user.click(screen.getByRole('button', { name: 'Confirm' }));
    };
    await payOnce();
    expect(await screen.findByRole('alert')).toHaveTextContent('Service unavailable');
    await payOnce();
    await waitFor(() => expect(transfer).toHaveBeenCalledTimes(2));
    expect(transfer.mock.calls[1][4]).toBe(transfer.mock.calls[0][4]);
  });

  it('does not pay without a PIN', async () => {
    const user = userEvent.setup();
    renderPage(<PayPage />, { route: '/pay', path: '/pay' });
    await user.type(screen.getByLabelText('UPI ID'), 'ravi@payverse');
    await user.type(screen.getByLabelText('Amount'), '10');
    await user.click(screen.getByRole('button', { name: /Pay ₹10.00/ }));
    await user.click(await screen.findByRole('button', { name: 'Cancel' }));
    expect(transfer).not.toHaveBeenCalled();
  });
});
