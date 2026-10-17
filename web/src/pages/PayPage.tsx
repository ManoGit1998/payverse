import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { paymentApi, transactionApi, userApi, walletApi } from '../api/endpoints';
import { errorMessage } from '../api/errors';
import type { DecodedQr, Payee, TransferResponse } from '../api/types';
import { usePin } from '../components/feedback';
import { TransferResult } from '../components/TransferResult';
import { AmountInput, Banner, Button, Card, ErrorBanner, PageHeader, Tabs, TextInput } from '../components/ui';
import { formatMoney, isValidAmount } from '../lib/format';
import { useIdempotencyKey } from '../lib/useIdempotencyKey';

type Mode = 'VPA' | 'MOBILE' | 'QR';
const VPA = /^[a-z0-9._-]{3,}@[a-z]{3,}$/i;
const MOBILE = /^[6-9]\d{9}$/;

export default function PayPage() {
  const [params] = useSearchParams();
  const [mode, setMode] = useState<Mode>((params.get('mode') as Mode) || 'VPA');
  const [vpa, setVpa] = useState(params.get('vpa') ?? '');
  const [mobile, setMobile] = useState('');
  const [qrPayload, setQrPayload] = useState('');
  const [qr, setQr] = useState<DecodedQr | null>(null);
  const [amount, setAmount] = useState(params.get('amount') ?? '');
  const [note, setNote] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<TransferResponse | null>(null);
  const askPin = usePin();
  const idempotency = useIdempotencyKey();

  const wallet = useQuery({ queryKey: ['wallet'], queryFn: walletApi.wallet });
  const beneficiaries = useQuery({ queryKey: ['beneficiaries'], queryFn: userApi.beneficiaries });
  const resolved = useQuery({
    queryKey: ['resolve-vpa', vpa.toLowerCase()],
    queryFn: () => paymentApi.resolveVpa(vpa.trim().toLowerCase()),
    enabled: mode === 'VPA' && VPA.test(vpa.trim()),
    retry: false,
  });

  const fixedAmount = mode === 'QR' && qr?.amount ? qr.amount.amount : null;
  const payee: Payee | null = useMemo(() => {
    if (mode === 'VPA') return VPA.test(vpa.trim()) ? { type: 'VPA', value: vpa.trim().toLowerCase() } : null;
    if (mode === 'MOBILE') return MOBILE.test(mobile) ? { type: 'MOBILE', value: mobile } : null;
    return qr ? { type: 'QR', value: qrPayload.trim() } : null;
  }, [mode, vpa, mobile, qr, qrPayload]);
  const amountOk = fixedAmount !== null || isValidAmount(amount);
  const insufficient = wallet.data && amountOk && Number(fixedAmount ?? amount) > Number(wallet.data.balance.amount);
  const payeeLabel = mode === 'VPA' ? (resolved.data?.payeeName ? `${resolved.data.payeeName} (${vpa})` : vpa)
    : mode === 'MOBILE' ? `+91 ${mobile}` : `${qr?.payeeName ?? qr?.vpa}`;

  const decode = async (action: () => Promise<DecodedQr>) => {
    setError(null);
    try {
      const decoded = await action();
      setQr(decoded);
      if (!qrPayload) setQrPayload(`upi://pay?pa=${decoded.vpa}${decoded.amount ? `&am=${decoded.amount.amount}` : ''}&cu=INR`);
    } catch (e) {
      setQr(null);
      setError(e);
    }
  };

  const pay = async () => {
    if (!payee || !amountOk) return;
    const sendAmount = fixedAmount ? undefined : amount;
    const pin = await askPin(`Pay ${formatMoney(fixedAmount ?? amount)}`, <>to <strong>{payeeLabel}</strong></>);
    if (!pin) return;
    setBusy(true);
    setError(null);
    try {
      const key = idempotency.keyFor({ payee, sendAmount, note });
      const res = await transactionApi.transfer(payee, sendAmount, note, pin, key);
      setResult(res.data);
      idempotency.reset();
    } catch (e) {
      setError(e); // same payload → same key on retry, so the server never pays twice
    } finally {
      setBusy(false);
    }
  };

  const reset = () => {
    setResult(null);
    setAmount('');
    setNote('');
    setQr(null);
    setQrPayload('');
  };

  if (result) return <div className="stack"><PageHeader title="Pay" /><Card><TransferResult initial={result} onDone={reset} /></Card></div>;

  return (
    <div className="stack">
      <PageHeader title="Pay" subtitle={wallet.data ? <>Balance {formatMoney(wallet.data.balance)}</> : undefined} />
      <div className="grid-2">
        <Card>
          <Tabs<Mode> value={mode} onChange={(m) => { setMode(m); setError(null); }}
                      options={[{ value: 'VPA', label: 'UPI ID' }, { value: 'MOBILE', label: 'Mobile' }, { value: 'QR', label: 'QR code' }]} />
          <form className="form" onSubmit={(e) => { e.preventDefault(); void pay(); }}>
            {mode === 'VPA' && (
              <TextInput label="UPI ID" placeholder="name@payverse" value={vpa} autoFocus onChange={(e) => setVpa(e.target.value.trim())}
                         hint={resolved.data ? <>✓ {resolved.data.payeeName}</> : resolved.error ? undefined : 'Only @payverse IDs can be paid in this simulation'}
                         error={resolved.error ? errorMessage(resolved.error) : null} />
            )}
            {mode === 'MOBILE' && (
              <TextInput label="Mobile number" inputMode="numeric" maxLength={10} placeholder="98765 43210" value={mobile} autoFocus
                         onChange={(e) => setMobile(e.target.value.replace(/\D/g, ''))} hint="The person must have a PayVerse account" />
            )}
            {mode === 'QR' && (
              <div className="stack">
                <TextInput label="UPI QR text" placeholder="upi://pay?pa=shop@payverse&am=149.50" value={qrPayload}
                           onChange={(e) => { setQrPayload(e.target.value); setQr(null); }}
                           onBlur={() => qrPayload.startsWith('upi://') && void decode(() => paymentApi.decodeQr(qrPayload.trim()))} />
                <label className="btn btn-secondary" style={{ alignSelf: 'flex-start' }}>
                  📷 Upload a QR image
                  <input type="file" accept="image/png,image/jpeg" className="sr-only"
                         onChange={(e) => { const f = e.target.files?.[0]; if (f) { setQrPayload(''); void decode(() => paymentApi.decodeQrImage(f)); } }} />
                </label>
                {qr && (
                  <Banner tone="success">
                    <span>Paying <strong>{qr.payeeName ?? qr.vpa}</strong> ({qr.vpa}){qr.amount ? ` · fixed amount ${formatMoney(qr.amount)}` : ''}{qr.note ? ` · ${qr.note}` : ''}</span>
                  </Banner>
                )}
              </div>
            )}
            {fixedAmount ? null : (
              <AmountInput value={amount} onChange={setAmount} error={insufficient ? 'More than your wallet balance' : null}
                           hint={wallet.data ? `Today you can still spend ${formatMoney(Number(wallet.data.limits.dailyDebitLimit) - Number(wallet.data.limits.dailyDebitUsed))}` : undefined} />
            )}
            <TextInput label="Note (optional)" maxLength={100} value={note} onChange={(e) => setNote(e.target.value)} placeholder="Dinner, rent…" />
            <ErrorBanner error={error} />
            <Button type="submit" busy={busy} disabled={!payee || !amountOk || Boolean(insufficient) || wallet.data?.status === 'FROZEN'}>
              Pay {amountOk ? formatMoney(fixedAmount ?? amount) : ''}
            </Button>
          </form>
        </Card>
        <Card title="Saved contacts">
          {beneficiaries.data?.filter((b) => b.type !== 'BANK_ACCOUNT').length ? (
            <ul className="list">
              {beneficiaries.data.filter((b) => b.type !== 'BANK_ACCOUNT').map((b) => (
                <li key={b.id} className="list-item clickable" tabIndex={0}
                    onClick={() => { if (b.type === 'VPA') { setMode('VPA'); setVpa(b.value); } else { setMode('MOBILE'); setMobile(b.value.replace(/\D/g, '').slice(-10)); } }}>
                  <span className="avatar" aria-hidden>{(b.nickname ?? b.value)[0]?.toUpperCase()}</span>
                  <div className="list-main"><div className="list-title">{b.nickname ?? b.value}</div><div className="list-sub">{b.value}</div></div>
                </li>
              ))}
            </ul>
          ) : <p className="muted small">Save people you pay often in Profile → Beneficiaries.</p>}
        </Card>
      </div>
    </div>
  );
}
