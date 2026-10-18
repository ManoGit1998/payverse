import { useState } from 'react';
import { Navigate, useNavigate } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { authApi, userApi } from '../api/endpoints';
import type { IdDocumentType } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Banner, Button, ErrorBanner, Select, Spinner, TextInput } from '../components/ui';
import { AuthLayout } from './LoginPage';
import { waitForWalletTier } from '../lib/waitForWalletTier';

const DOCS: { value: IdDocumentType; label: string; placeholder: string }[] = [
  { value: 'PAN', label: 'PAN', placeholder: 'ABCDE1234F' },
  { value: 'VOTER_ID', label: 'Voter ID', placeholder: 'ABC1234567' },
  { value: 'DRIVING_LICENSE', label: 'Driving licence', placeholder: 'KA0120230001234' },
];

/** First run: set the transaction PIN, then minimum KYC (without it the wallet cannot move money). */
export default function OnboardingPage() {
  const { me, refreshMe } = useAuth();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const profile = useQuery({ queryKey: ['profile'], queryFn: userApi.profile });
  const [pin, setPin] = useState('');
  const [confirm, setConfirm] = useState('');
  const [doc, setDoc] = useState<IdDocumentType>('PAN');
  const [number, setNumber] = useState('');
  const [name, setName] = useState('');
  const [dob, setDob] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  if (!me || profile.isLoading) return <AuthLayout><Spinner /></AuthLayout>;
  const needsPin = !me.pinSet;
  const needsKyc = profile.data?.kycTier === 'NONE';
  if (!needsPin && !needsKyc) return <Navigate to="/" replace />;

  const submit = async (action: () => Promise<void>) => {
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
      <p className="small muted">Step {needsPin ? 1 : 2} of 2</p>
      {needsPin ? (
        <form className="form" onSubmit={(e) => { e.preventDefault(); void submit(async () => { await authApi.setPin(pin); await refreshMe(); }); }}>
          <h1>Set your transaction PIN</h1>
          <p className="muted">You'll enter it to approve every payment. 4 or 6 digits; avoid 1234, 0000 and the like.</p>
          <TextInput label="PIN" type="password" inputMode="numeric" maxLength={6} autoFocus value={pin} onChange={(e) => setPin(e.target.value.replace(/\D/g, ''))} />
          <TextInput label="Confirm PIN" type="password" inputMode="numeric" maxLength={6} value={confirm}
                     onChange={(e) => setConfirm(e.target.value.replace(/\D/g, ''))} error={confirm && confirm !== pin ? 'PINs do not match' : null} />
          <ErrorBanner error={error} />
          <Button type="submit" busy={busy} disabled={!/^\d{4}$|^\d{6}$/.test(pin) || pin !== confirm}>Set PIN</Button>
        </form>
      ) : (
        <form className="form" onSubmit={(e) => {
          e.preventDefault();
          void submit(async () => {
            await userApi.minKyc({ documentType: doc, documentNumber: number.trim().toUpperCase(), fullName: name.trim(), dateOfBirth: dob });
            await waitForWalletTier('NONE');
            await queryClient.invalidateQueries();
            navigate('/', { replace: true });
          });
        }}>
          <h1>Verify your identity</h1>
          <p className="muted">Minimum KYC unlocks a wallet of up to ₹10,000.</p>
          <Select label="Document" value={doc} onChange={(e) => setDoc(e.target.value as IdDocumentType)}>
            {DOCS.map((d) => <option key={d.value} value={d.value}>{d.label}</option>)}
          </Select>
          <TextInput label="Document number" value={number} placeholder={DOCS.find((d) => d.value === doc)?.placeholder} onChange={(e) => setNumber(e.target.value.toUpperCase())} />
          <TextInput label="Full name (as on the document)" value={name} onChange={(e) => setName(e.target.value)} />
          <TextInput label="Date of birth" type="date" value={dob} max={new Date().toISOString().slice(0, 10)} onChange={(e) => setDob(e.target.value)} />
          <p className="mock-note">Mock verifier: numbers containing 9999 are rejected.</p>
          <ErrorBanner error={error} />
          <Button type="submit" busy={busy} disabled={!number || name.trim().length < 2 || !dob}>Verify</Button>
          <Banner tone="info">You can also do this later from Profile → KYC.</Banner>
          <Button type="button" variant="ghost" onClick={() => navigate('/', { replace: true })}>Skip for now</Button>
        </form>
      )}
    </AuthLayout>
  );
}
