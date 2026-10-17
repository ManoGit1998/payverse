import { useEffect, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { authApi } from '../api/endpoints';
import { useAuth } from '../auth/AuthContext';
import { Button, ErrorBanner, TextInput } from '../components/ui';

const MOBILE = /^[6-9]\d{9}$/;

export function AuthLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="auth-page">
      <section className="auth-hero">
        <div className="brand" style={{ color: '#fff', padding: 0 }}><span className="brand-mark" style={{ background: '#fff', color: '#4f46e5' }}>P</span> PayVerse</div>
        <div>
          <h1>Money, moved in seconds.</h1>
          <ul>
            <li>Pay anyone by UPI ID, mobile number or QR</li>
            <li>Recharges, bills and auto-pay</li>
            <li>Cashback, scratch cards and referral bonuses</li>
          </ul>
        </div>
        <p className="small" style={{ opacity: 0.8 }}>Simulation — no real money moves. OTPs are printed by the mock SMS sender (<code>make otp</code>).</p>
      </section>
      <section className="auth-panel">
        <div className="card auth-card">{children}</div>
      </section>
    </div>
  );
}

export default function LoginPage() {
  const { signIn } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [step, setStep] = useState<'mobile' | 'otp'>('mobile');
  const [mobile, setMobile] = useState('');
  const [otp, setOtp] = useState('');
  const [referral, setReferral] = useState(new URLSearchParams(location.search).get('ref') ?? '');
  const [showReferral, setShowReferral] = useState(Boolean(referral));
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [resendIn, setResendIn] = useState(0);

  useEffect(() => {
    if (resendIn <= 0) return;
    const t = setTimeout(() => setResendIn((s) => s - 1), 1000);
    return () => clearTimeout(t);
  }, [resendIn]);

  const requestOtp = async () => {
    setError(null);
    setBusy(true);
    try {
      await authApi.requestOtp(mobile);
      setStep('otp');
      setResendIn(30);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  const verify = async () => {
    setError(null);
    setBusy(true);
    try {
      const tokens = await authApi.verifyOtp(mobile, otp, referral.trim() || undefined);
      const me = await signIn(tokens);
      const from = (location.state as { from?: string } | null)?.from;
      navigate(!me.pinSet ? '/onboarding' : from ?? '/', { replace: true });
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthLayout>
      {step === 'mobile' ? (
        <form className="form" onSubmit={(e) => { e.preventDefault(); if (MOBILE.test(mobile)) void requestOtp(); }}>
          <div>
            <h1>Sign in or create an account</h1>
            <p className="muted">We'll send a one-time code to your mobile.</p>
          </div>
          <TextInput label="Mobile number" inputMode="numeric" autoComplete="tel-national" placeholder="98765 43210" autoFocus maxLength={10}
                     value={mobile} onChange={(e) => setMobile(e.target.value.replace(/\D/g, ''))}
                     error={mobile.length === 10 && !MOBILE.test(mobile) ? 'Enter a valid Indian mobile number' : null} />
          {showReferral ? (
            <TextInput label="Referral code (new accounts)" value={referral} maxLength={12}
                       onChange={(e) => setReferral(e.target.value.toUpperCase())} hint="You and your friend both get a bonus after KYC." />
          ) : (
            <button type="button" className="link" style={{ alignSelf: 'flex-start' }} onClick={() => setShowReferral(true)}>Have a referral code?</button>
          )}
          <ErrorBanner error={error} />
          <Button type="submit" busy={busy} disabled={!MOBILE.test(mobile)} className="btn-block">Send OTP</Button>
          <Link to="/forgot-pin" className="small">Forgot your transaction PIN?</Link>
        </form>
      ) : (
        <form className="form" onSubmit={(e) => { e.preventDefault(); if (otp.length >= 4) void verify(); }}>
          <div>
            <h1>Enter the code</h1>
            <p className="muted">Sent to +91 {mobile.slice(0, 5)} {mobile.slice(5)} · <button type="button" className="link" onClick={() => { setStep('mobile'); setOtp(''); }}>change</button></p>
          </div>
          <TextInput label="One-time password" inputMode="numeric" autoComplete="one-time-code" autoFocus maxLength={8}
                     value={otp} onChange={(e) => setOtp(e.target.value.replace(/\D/g, ''))} placeholder="6-digit code" />
          <ErrorBanner error={error} />
          <Button type="submit" busy={busy} disabled={otp.length < 4} className="btn-block">Verify and continue</Button>
          <p className="small muted">
            Didn't get it?{' '}
            {resendIn > 0 ? `Resend in ${resendIn}s` : <button type="button" className="link" onClick={() => void requestOtp()}>Resend code</button>}
          </p>
        </form>
      )}
    </AuthLayout>
  );
}
