import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderPage } from '../test/render';

const requestOtp = vi.fn(async () => ({ expiresAt: '', expiresInSeconds: 300, message: 'sent' }));
const verifyOtp = vi.fn(async () => ({ accessToken: 'a', refreshToken: 'r', userId: 'u-1', newUser: true, pinSet: false }));
const signIn = vi.fn(async () => ({ userId: 'u-1', roles: ['USER'], deviceId: 'd', pinSet: false }));

vi.mock('../api/endpoints', () => ({ authApi: { requestOtp: (...a: unknown[]) => requestOtp(...(a as [])), verifyOtp: (...a: unknown[]) => verifyOtp(...(a as [])) } }));
vi.mock('../auth/AuthContext', () => ({ useAuth: () => ({ signIn }) }));

const { default: LoginPage } = await import('./LoginPage');

describe('LoginPage', () => {
  it('signs a new user in with an OTP and a referral code, then goes to onboarding', async () => {
    const user = userEvent.setup();
    renderPage(<LoginPage />, { route: '/login', path: '/login' });

    const send = screen.getByRole('button', { name: 'Send OTP' });
    expect(send).toBeDisabled();
    await user.type(screen.getByLabelText('Mobile number'), '5876543210');
    expect(screen.getByText('Enter a valid Indian mobile number')).toBeInTheDocument();
    await user.clear(screen.getByLabelText('Mobile number'));
    await user.type(screen.getByLabelText('Mobile number'), '9876543210');
    await user.click(screen.getByText('Have a referral code?'));
    await user.type(screen.getByLabelText(/Referral code/), 'abcd2345');
    await user.click(send);

    expect(requestOtp).toHaveBeenCalledWith('9876543210');
    await user.type(await screen.findByLabelText('One-time password'), '123456');
    await user.click(screen.getByRole('button', { name: 'Verify and continue' }));

    expect(verifyOtp).toHaveBeenCalledWith('9876543210', '123456', 'ABCD2345');
    expect(await screen.findByText('onboarding page')).toBeInTheDocument();
  });
});
