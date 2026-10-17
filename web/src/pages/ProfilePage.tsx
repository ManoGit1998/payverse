import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { authApi, paymentApi, userApi } from '../api/endpoints';
import { errorMessage } from '../api/errors';
import type { BeneficiaryType, IdDocumentType, Qr } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { useToast } from '../components/feedback';
import { AmountInput, Badge, Banner, Button, Card, Empty, ErrorBanner, KeyValue, PageHeader, Select, Spinner, Tabs, TextInput } from '../components/ui';
import { formatDate, formatDateTime, formatMoney, humanize, isValidAmount, relativeTime } from '../lib/format';

type Tab = 'profile' | 'kyc' | 'banks' | 'beneficiaries' | 'upi' | 'security';

export default function ProfilePage() {
  const [params, setParams] = useSearchParams();
  const tab = (params.get('tab') as Tab) || 'profile';
  return (
    <div className="stack">
      <PageHeader title="Profile & security" />
      <Tabs<Tab> value={tab} onChange={(t) => setParams({ tab: t })} options={[
        { value: 'profile', label: 'Profile' }, { value: 'kyc', label: 'KYC' }, { value: 'banks', label: 'Bank accounts' },
        { value: 'beneficiaries', label: 'Beneficiaries' }, { value: 'upi', label: 'UPI & QR' }, { value: 'security', label: 'Security' },
      ]} />
      {tab === 'profile' && <ProfileTab />}
      {tab === 'kyc' && <KycTab />}
      {tab === 'banks' && <BanksTab />}
      {tab === 'beneficiaries' && <BeneficiariesTab />}
      {tab === 'upi' && <UpiTab />}
      {tab === 'security' && <SecurityTab />}
    </div>
  );
}

function ProfileTab() {
  const profile = useQuery({ queryKey: ['profile'], queryFn: userApi.profile });
  const [form, setForm] = useState({ fullName: '', email: '', dateOfBirth: '' });
  const toast = useToast();
  const queryClient = useQueryClient();
  useEffect(() => {
    if (profile.data) setForm({ fullName: profile.data.fullName ?? '', email: profile.data.email ?? '', dateOfBirth: profile.data.dateOfBirth ?? '' });
  }, [profile.data]);
  const save = useMutation({
    mutationFn: () => userApi.updateProfile({ fullName: form.fullName || undefined, email: form.email || undefined, dateOfBirth: form.dateOfBirth || undefined }),
    onSuccess: () => { toast('Profile saved'); void queryClient.invalidateQueries({ queryKey: ['profile'] }); },
  });
  if (profile.isLoading) return <Spinner />;
  if (profile.error) return <ErrorBanner error={profile.error} />;
  const locked = profile.data?.kycTier !== 'NONE';
  return (
    <div className="grid-2">
      <Card title="Your details">
        <form className="form" onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
          <TextInput label="Full name" value={form.fullName} disabled={locked} hint={locked ? 'Locked after KYC' : undefined} onChange={(e) => setForm({ ...form, fullName: e.target.value })} />
          <TextInput label="Email" type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} />
          <TextInput label="Date of birth" type="date" value={form.dateOfBirth} disabled={locked} onChange={(e) => setForm({ ...form, dateOfBirth: e.target.value })} />
          <ErrorBanner error={save.error} />
          <Button type="submit" busy={save.isPending}>Save</Button>
        </form>
      </Card>
      <Card title="Account">
        <KeyValue items={[['Mobile', profile.data?.mobileMasked], ['KYC tier', <Badge key="t" value={profile.data!.kycTier} />], ['Member since', formatDate(profile.data?.createdAt)]]} />
      </Card>
    </div>
  );
}

function KycTab() {
  const kyc = useQuery({ queryKey: ['kyc'], queryFn: userApi.kyc });
  const queryClient = useQueryClient();
  const toast = useToast();
  const [doc, setDoc] = useState<IdDocumentType>('PAN');
  const [number, setNumber] = useState('');
  const [name, setName] = useState('');
  const [dob, setDob] = useState('');
  const [aadhaar, setAadhaar] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const done = () => { toast('KYC verified'); void queryClient.invalidateQueries(); };
  const minKyc = useMutation({ mutationFn: () => userApi.minKyc({ documentType: doc, documentNumber: number.toUpperCase(), fullName: name, dateOfBirth: dob }), onSuccess: done });
  const fullKyc = useMutation({ mutationFn: () => userApi.fullKyc(aadhaar, file!), onSuccess: done });
  if (kyc.isLoading) return <Spinner />;
  if (kyc.error) return <ErrorBanner error={kyc.error} />;
  const tier = kyc.data!.tier;
  return (
    <div className="grid-2">
      <Card title={<>KYC level <Badge value={tier} /></>}>
        {tier === 'NONE' && (
          <form className="form" onSubmit={(e) => { e.preventDefault(); minKyc.mutate(); }}>
            <p className="muted">Minimum KYC: wallet up to ₹10,000.</p>
            <Select label="Document" value={doc} onChange={(e) => setDoc(e.target.value as IdDocumentType)}>
              <option value="PAN">PAN</option><option value="VOTER_ID">Voter ID</option><option value="DRIVING_LICENSE">Driving licence</option>
            </Select>
            <TextInput label="Document number" value={number} onChange={(e) => setNumber(e.target.value.toUpperCase())} />
            <TextInput label="Name on document" value={name} onChange={(e) => setName(e.target.value)} />
            <TextInput label="Date of birth" type="date" value={dob} onChange={(e) => setDob(e.target.value)} />
            <ErrorBanner error={minKyc.error} />
            <Button type="submit" busy={minKyc.isPending} disabled={!number || !name || !dob}>Verify</Button>
          </form>
        )}
        {tier === 'MIN_KYC' && (
          <form className="form" onSubmit={(e) => { e.preventDefault(); fullKyc.mutate(); }}>
            <p className="muted">Full KYC: wallet up to ₹2,00,000 and ₹1,00,000 a day.</p>
            <TextInput label="Aadhaar number" inputMode="numeric" maxLength={12} value={aadhaar} onChange={(e) => setAadhaar(e.target.value.replace(/\D/g, ''))}
                       hint="12 digits (checked with the Verhoeff checksum)" />
            <label className="field">
              <span style={{ fontWeight: 600, fontSize: '.88rem' }}>Aadhaar document (JPEG, PNG or PDF, ≤ 5 MB)</span>
              <input type="file" accept="image/jpeg,image/png,application/pdf" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
            </label>
            {file && file.size > 5 * 1024 * 1024 && <p className="field-message">That file is larger than 5 MB.</p>}
            <ErrorBanner error={fullKyc.error} />
            <Button type="submit" busy={fullKyc.isPending} disabled={aadhaar.length !== 12 || !file || file.size > 5 * 1024 * 1024}>Upgrade to full KYC</Button>
          </form>
        )}
        {tier === 'FULL_KYC' && <Banner tone="success">You're fully verified.</Banner>}
        <p className="mock-note" style={{ marginTop: 12 }}>Mock verifier: document numbers containing 9999 are rejected; documents are discarded.</p>
      </Card>
      <Card title="Verification history">
        {kyc.data!.history.length ? (
          <ul className="list">
            {kyc.data!.history.map((h) => (
              <li key={h.id} className="list-item">
                <div className="list-main"><div className="list-title">{humanize(h.documentType)} · {h.documentMasked}</div><div className="list-sub">{formatDateTime(h.createdAt)}{h.reason ? ` · ${h.reason}` : ''}</div></div>
                <Badge value={h.status} />
              </li>
            ))}
          </ul>
        ) : <Empty title="No KYC attempts yet" />}
      </Card>
    </div>
  );
}

function BanksTab() {
  const accounts = useQuery({ queryKey: ['bank-accounts'], queryFn: userApi.bankAccounts });
  const [form, setForm] = useState({ accountNumber: '', ifsc: '', holderName: '' });
  const toast = useToast();
  const queryClient = useQueryClient();
  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['bank-accounts'] });
  const link = useMutation({ mutationFn: () => userApi.linkBankAccount(form), onSuccess: () => { toast('Bank account linked'); setForm({ accountNumber: '', ifsc: '', holderName: '' }); refresh(); } });
  const action = useMutation({ mutationFn: (fn: () => Promise<unknown>) => fn(), onSuccess: refresh, onError: (e) => toast(errorMessage(e), 'danger') });
  const valid = /^\d{9,18}$/.test(form.accountNumber) && /^[A-Z]{4}0[A-Z0-9]{6}$/.test(form.ifsc) && form.holderName.trim().length >= 2;
  return (
    <div className="grid-2">
      <Card title="Linked accounts">
        {accounts.isLoading ? <Spinner /> : !accounts.data?.length ? <Empty title="No bank accounts" /> : (
          <ul className="list">
            {accounts.data.map((a) => (
              <li key={a.id} className="list-item">
                <span className="avatar">🏦</span>
                <div className="list-main"><div className="list-title">{a.holderName} · {a.maskedAccountNumber}</div><div className="list-sub">{a.ifsc} · {a.bankCode}</div></div>
                {a.primary ? <Badge value="primary" tone="info" /> : <Button variant="ghost" onClick={() => action.mutate(() => userApi.makePrimary(a.id))}>Make primary</Button>}
                <Button variant="ghost" onClick={() => action.mutate(() => userApi.unlinkBankAccount(a.id))}>Unlink</Button>
              </li>
            ))}
          </ul>
        )}
      </Card>
      <Card title="Link a bank account">
        <form className="form" onSubmit={(e) => { e.preventDefault(); link.mutate(); }}>
          <TextInput label="Account number" inputMode="numeric" value={form.accountNumber} onChange={(e) => setForm({ ...form, accountNumber: e.target.value.replace(/\D/g, '') })} />
          <TextInput label="IFSC" value={form.ifsc} maxLength={11} placeholder="HDFC0001234" onChange={(e) => setForm({ ...form, ifsc: e.target.value.toUpperCase() })} />
          <TextInput label="Account holder name" value={form.holderName} onChange={(e) => setForm({ ...form, holderName: e.target.value })} />
          <ErrorBanner error={link.error} />
          <Button type="submit" busy={link.isPending} disabled={!valid}>Link account</Button>
          <p className="mock-note">Simulation: no penny-drop verification. Up to 5 accounts.</p>
        </form>
      </Card>
    </div>
  );
}

function BeneficiariesTab() {
  const list = useQuery({ queryKey: ['beneficiaries'], queryFn: userApi.beneficiaries });
  const [form, setForm] = useState<{ type: BeneficiaryType; value: string; ifsc: string; nickname: string }>({ type: 'VPA', value: '', ifsc: '', nickname: '' });
  const toast = useToast();
  const queryClient = useQueryClient();
  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['beneficiaries'] });
  const add = useMutation({
    mutationFn: () => userApi.addBeneficiary({ type: form.type, value: form.value.trim(), ifsc: form.type === 'BANK_ACCOUNT' ? form.ifsc : undefined, nickname: form.nickname || undefined }),
    onSuccess: () => { toast('Beneficiary saved'); setForm({ ...form, value: '', ifsc: '', nickname: '' }); refresh(); },
  });
  const remove = useMutation({ mutationFn: (id: string) => userApi.removeBeneficiary(id), onSuccess: refresh });
  return (
    <div className="grid-2">
      <Card title="Saved beneficiaries">
        {list.isLoading ? <Spinner /> : !list.data?.length ? <Empty title="Nobody saved yet" /> : (
          <ul className="list">
            {list.data.map((b) => (
              <li key={b.id} className="list-item">
                <div className="list-main"><div className="list-title">{b.nickname ?? b.value}</div><div className="list-sub">{humanize(b.type)} · {b.value}{b.ifsc ? ` · ${b.ifsc}` : ''}</div></div>
                <Button variant="ghost" onClick={() => remove.mutate(b.id)}>Remove</Button>
              </li>
            ))}
          </ul>
        )}
      </Card>
      <Card title="Add beneficiary">
        <form className="form" onSubmit={(e) => { e.preventDefault(); add.mutate(); }}>
          <Select label="Type" value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value as BeneficiaryType })}>
            <option value="VPA">UPI ID</option><option value="MOBILE">Mobile</option><option value="BANK_ACCOUNT">Bank account</option>
          </Select>
          <TextInput label={form.type === 'VPA' ? 'UPI ID' : form.type === 'MOBILE' ? 'Mobile number' : 'Account number'} value={form.value} onChange={(e) => setForm({ ...form, value: e.target.value })} />
          {form.type === 'BANK_ACCOUNT' && <TextInput label="IFSC" value={form.ifsc} maxLength={11} onChange={(e) => setForm({ ...form, ifsc: e.target.value.toUpperCase() })} />}
          <TextInput label="Nickname" value={form.nickname} maxLength={50} onChange={(e) => setForm({ ...form, nickname: e.target.value })} />
          <ErrorBanner error={add.error} />
          <Button type="submit" busy={add.isPending} disabled={!form.value.trim()}>Save</Button>
        </form>
      </Card>
    </div>
  );
}

function QrImage({ qr }: { qr: Qr }) {
  const [src, setSrc] = useState<string | null>(null);
  useEffect(() => {
    let url: string | null = null;
    paymentApi.qrImageUrl(qr).then((u) => { url = u; setSrc(u); }).catch(() => setSrc(null));
    return () => { if (url) URL.revokeObjectURL(url); };
  }, [qr]);
  return src ? <img className="qr-image" src={src} alt={`QR code for ${qr.vpa}`} /> : <div className="qr-image"><Spinner label="" /></div>;
}

function UpiTab() {
  const vpas = useQuery({ queryKey: ['vpas'], queryFn: paymentApi.myVpas });
  const [name, setName] = useState('');
  const [staticQr, setStaticQr] = useState<Qr | null>(null);
  const [dynamicQr, setDynamicQr] = useState<Qr | null>(null);
  const [amount, setAmount] = useState('');
  const [note, setNote] = useState('');
  const toast = useToast();
  const queryClient = useQueryClient();
  const create = useMutation({ mutationFn: () => paymentApi.createVpa(name.trim().toLowerCase()), onSuccess: (v) => { toast(`${v.vpa} created`); setName(''); void queryClient.invalidateQueries({ queryKey: ['vpas'] }); } });
  const makeStatic = useMutation({ mutationFn: paymentApi.staticQr, onSuccess: setStaticQr });
  const makeDynamic = useMutation({ mutationFn: () => paymentApi.dynamicQr(amount, note, 15), onSuccess: setDynamicQr });
  return (
    <div className="stack">
      <div className="grid-2">
        <Card title="My UPI IDs">
          {vpas.isLoading ? <Spinner /> : (
            <ul className="list">
              {vpas.data?.map((v) => (
                <li key={v.id} className="list-item"><div className="list-main"><div className="list-title">{v.vpa}</div><div className="list-sub">created {formatDate(v.createdAt)}</div></div>{v.defaultVpa && <Badge value="default" tone="info" />}</li>
              ))}
            </ul>
          )}
          <form className="row" style={{ alignItems: 'flex-end', marginTop: 12 }} onSubmit={(e) => { e.preventDefault(); create.mutate(); }}>
            <div style={{ flex: 1 }}><TextInput label="New UPI ID" value={name} placeholder="asha.verma" hint={name ? `${name.toLowerCase()}@payverse` : 'becomes name@payverse'} onChange={(e) => setName(e.target.value)} /></div>
            <Button type="submit" busy={create.isPending} disabled={name.trim().length < 3}>Create</Button>
          </form>
          <ErrorBanner error={create.error} />
        </Card>
        <Card title="Receive with a QR">
          <div className="stack" style={{ alignItems: 'center' }}>
            {staticQr ? <><QrImage qr={staticQr} /><code className="small">{staticQr.payload}</code></> : <p className="muted">Anyone can scan it and pay you any amount.</p>}
            <Button variant="secondary" busy={makeStatic.isPending} onClick={() => makeStatic.mutate()}>{staticQr ? 'Refresh QR' : 'Show my QR'}</Button>
            <ErrorBanner error={makeStatic.error} />
          </div>
        </Card>
      </div>
      <Card title="Collect a fixed amount (dynamic QR, 15 minutes)">
        <div className="grid-2">
          <form className="form" onSubmit={(e) => { e.preventDefault(); makeDynamic.mutate(); }}>
            <AmountInput value={amount} onChange={setAmount} />
            <TextInput label="Note" value={note} maxLength={100} onChange={(e) => setNote(e.target.value)} />
            <ErrorBanner error={makeDynamic.error} />
            <Button type="submit" busy={makeDynamic.isPending} disabled={!isValidAmount(amount)}>Create QR</Button>
          </form>
          {dynamicQr && (
            <div className="stack" style={{ alignItems: 'center' }}>
              <QrImage qr={dynamicQr} />
              <strong>{formatMoney(dynamicQr.amount)}</strong>
              <span className="small muted">expires {formatDateTime(dynamicQr.expiresAt)}</span>
            </div>
          )}
        </div>
      </Card>
    </div>
  );
}

function SecurityTab() {
  const { me, signOut } = useAuth();
  const navigate = useNavigate();
  const devices = useQuery({ queryKey: ['devices'], queryFn: authApi.devices });
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const toast = useToast();
  const queryClient = useQueryClient();
  const change = useMutation({ mutationFn: () => authApi.changePin(current, next), onSuccess: () => { toast('PIN changed'); setCurrent(''); setNext(''); } });
  const revoke = useMutation({ mutationFn: (id: string) => authApi.revokeDevice(id), onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['devices'] }) });
  return (
    <div className="grid-2">
      <Card title="Change transaction PIN">
        <form className="form" onSubmit={(e) => { e.preventDefault(); change.mutate(); }}>
          <TextInput label="Current PIN" type="password" inputMode="numeric" maxLength={6} value={current} onChange={(e) => setCurrent(e.target.value.replace(/\D/g, ''))} />
          <TextInput label="New PIN" type="password" inputMode="numeric" maxLength={6} value={next} onChange={(e) => setNext(e.target.value.replace(/\D/g, ''))} />
          <ErrorBanner error={change.error} />
          <Button type="submit" busy={change.isPending} disabled={!current || !/^\d{4}$|^\d{6}$/.test(next)}>Change PIN</Button>
        </form>
      </Card>
      <Card title="Devices" action={<Button variant="secondary" onClick={async () => { await signOut(); navigate('/login'); }}>Sign out</Button>}>
        {devices.isLoading ? <Spinner /> : (
          <ul className="list">
            {devices.data?.map((d) => (
              <li key={d.deviceId} className="list-item">
                <span className="avatar">💻</span>
                <div className="list-main"><div className="list-title">{d.name}{d.current ? ' (this device)' : ''}</div><div className="list-sub">bound {formatDate(d.boundAt)} · last seen {relativeTime(d.lastSeenAt)}</div></div>
                {!d.current && <Button variant="ghost" busy={revoke.isPending && revoke.variables === d.deviceId} onClick={() => revoke.mutate(d.deviceId)}>Revoke</Button>}
              </li>
            ))}
          </ul>
        )}
        <p className="small muted">Revoking a device ends its sessions. Device ID of this browser: <code>{me?.deviceId}</code></p>
      </Card>
    </div>
  );
}
