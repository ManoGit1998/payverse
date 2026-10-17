import { useState } from 'react';
import { Link } from 'react-router';
import { authApi } from '../api/endpoints';
import { Banner, Button, ErrorBanner, TextInput } from '../components/ui';
import { AuthLayout } from './LoginPage';

export default function ForgotPinPage() {
  const [step, setStep] = useState<'mobile' | 'reset' | 'done'>('mobile');
  const [mobile, setMobile] = useState('');
  const [otp, setOtp] = useState('');
  const [pin, setPin] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const run = async (action: () => Promise<void>) => {
    setError(null);
    setBusy(true);
    try {
      await action();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthLayout>
      {step === 'mobile' && (
        <form className="form" onSubmit={(e) => { e.preventDefault(); void run(async () => { await authApi.requestPinReset(mobile); setStep('reset'); }); }}>
          <h1>Reset your PIN</h1>
          <p className="muted">We'll send an OTP to your registered mobile.</p>
          <TextInput label="Mobile number" inputMode="numeric" maxLength={10} autoFocus value={mobile} onChange={(e) => setMobile(e.target.value.replace(/\D/g, ''))} />
          <ErrorBanner error={error} />
          <Button type="submit" busy={busy} disabled={!/^[6-9]\d{9}$/.test(mobile)}>Send OTP</Button>
          <Link to="/login" className="small">Back to sign in</Link>
        </form>
      )}
      {step === 'reset' && (
        <form className="form" onSubmit={(e) => { e.preventDefault(); void run(async () => { await authApi.confirmPinReset(mobile, otp, pin); setStep('done'); }); }}>
          <h1>Choose a new PIN</h1>
          <TextInput label="OTP" inputMode="numeric" maxLength={8} autoFocus value={otp} onChange={(e) => setOtp(e.target.value.replace(/\D/g, ''))} />
          <TextInput label="New PIN (4 or 6 digits)" type="password" inputMode="numeric" maxLength={6} value={pin} onChange={(e) => setPin(e.target.value.replace(/\D/g, ''))} />
          <TextInput label="Confirm new PIN" type="password" inputMode="numeric" maxLength={6} value={confirm}
                     onChange={(e) => setConfirm(e.target.value.replace(/\D/g, ''))} error={confirm && confirm !== pin ? 'PINs do not match' : null} />
          <ErrorBanner error={error} />
          <Button type="submit" busy={busy} disabled={otp.length < 4 || !/^\d{4}$|^\d{6}$/.test(pin) || pin !== confirm}>Reset PIN</Button>
        </form>
      )}
      {step === 'done' && (
        <div className="form">
          <h1>PIN updated</h1>
          <Banner tone="success">Your new transaction PIN is active.</Banner>
          <Link to="/login" className="btn btn-primary">Sign in</Link>
        </div>
      )}
    </AuthLayout>
  );
}
