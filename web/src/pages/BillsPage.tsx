import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { billApi } from '../api/endpoints';
import type { BillCategory, BillPayment, Biller, FetchedBill, MandateFrequency } from '../api/types';
import { usePin, useToast } from '../components/feedback';
import { Amount, AmountInput, Badge, Banner, Button, Card, Empty, ErrorBanner, KeyValue, Modal, PageHeader, Select, Spinner, Tabs, TextInput } from '../components/ui';
import { formatDate, formatDateTime, formatMoney, humanize, isValidAmount } from '../lib/format';
import { useIdempotencyKey } from '../lib/useIdempotencyKey';

const CATEGORIES: { value: BillCategory; label: string; icon: string }[] = [
  { value: 'MOBILE', label: 'Mobile', icon: '📱' }, { value: 'ELECTRICITY', label: 'Electricity', icon: '💡' },
  { value: 'DTH', label: 'DTH', icon: '📺' }, { value: 'FASTAG', label: 'FASTag', icon: '🚗' }, { value: 'CREDIT_CARD', label: 'Credit card', icon: '💳' },
];
type Tab = 'pay' | 'history' | 'autopay';

export default function BillsPage() {
  const [tab, setTab] = useState<Tab>('pay');
  return (
    <div className="stack">
      <PageHeader title="Bills & recharge" subtitle="Billers are simulated (MockBillerAdapter)" />
      <Tabs<Tab> value={tab} onChange={setTab} options={[{ value: 'pay', label: 'Pay a bill' }, { value: 'history', label: 'History' }, { value: 'autopay', label: 'Auto-pay' }]} />
      {tab === 'pay' && <PayBill />}
      {tab === 'history' && <BillHistory />}
      {tab === 'autopay' && <AutoPay />}
    </div>
  );
}

function useBillers(category: BillCategory) {
  return useQuery({ queryKey: ['billers', category], queryFn: () => billApi.billers(category) });
}

function validAccount(biller: Biller | undefined, account: string) {
  if (!biller) return false;
  try {
    return new RegExp(biller.accountPattern).test(account.replace(/\s+/g, '').toUpperCase());
  } catch {
    return account.length > 3;
  }
}

function PayBill() {
  const [category, setCategory] = useState<BillCategory>('MOBILE');
  const billers = useBillers(category);
  const [billerCode, setBillerCode] = useState('');
  const [account, setAccount] = useState('');
  const [amount, setAmount] = useState('');
  const [bill, setBill] = useState<FetchedBill | null>(null);
  const [result, setResult] = useState<BillPayment | null>(null);
  const askPin = usePin();
  const toast = useToast();
  const queryClient = useQueryClient();
  const idempotency = useIdempotencyKey();

  const biller = billers.data?.find((b) => b.code === billerCode) ?? billers.data?.[0];
  const accountOk = validAccount(biller, account);
  const fetchBill = useMutation({
    mutationFn: () => billApi.fetch(biller!.code, account),
    onSuccess: (b) => { setBill(b); setAmount(b.amountDue.amount); },
  });
  const pay = useMutation({
    mutationFn: async () => {
      const pin = await askPin(`Pay ${formatMoney(amount)}`, <>{biller?.name} · {account}</>);
      if (!pin) return null;
      return billApi.pay(biller!.code, account, amount, pin, idempotency.keyFor({ code: biller!.code, account, amount }));
    },
    onSuccess: (r) => {
      if (!r) return;
      idempotency.reset();
      setResult(r.data);
      void queryClient.invalidateQueries({ queryKey: ['wallet'] });
      void queryClient.invalidateQueries({ queryKey: ['bills'] });
      if (r.data.status === 'SUCCESS') toast(`Paid ${formatMoney(r.data.amount)} to ${biller?.name}`);
    },
  });
  const amountOk = biller && isValidAmount(amount, biller.minAmount, biller.maxAmount);

  if (result) {
    const ok = result.status === 'SUCCESS';
    return (
      <Card>
        <div className="result">
          <div className={`result-icon ${ok ? 'success' : result.status === 'PENDING' ? 'warning' : 'danger'}`}>{ok ? '✓' : result.status === 'PENDING' ? '…' : '✕'}</div>
          <h2>{ok ? 'Bill paid' : result.status === 'PENDING' ? 'Payment processing' : 'Payment failed'}</h2>
          <p className="balance" style={{ fontSize: '2rem' }}>{formatMoney(result.amount)}</p>
          {!ok && result.refunded && <Banner tone="info">The biller declined it — {formatMoney(result.amount)} was refunded to your wallet.</Banner>}
          {!ok && !result.refunded && result.failureCode && <Banner tone="danger">{humanize(result.failureCode)}</Banner>}
          <KeyValue items={[['Biller', biller?.name], ['Account', result.accountRefMasked], ['Biller reference', result.billerRef]]} />
          <div className="row" style={{ justifyContent: 'center', marginTop: 16 }}><Button onClick={() => { setResult(null); setBill(null); setAmount(''); }}>Pay another</Button></div>
        </div>
      </Card>
    );
  }

  return (
    <div className="grid-2">
      <Card>
        <div className="quick-actions" style={{ marginBottom: 16 }}>
          {CATEGORIES.map((c) => (
            <button key={c.value} className="quick-action" style={c.value === category ? { borderColor: 'var(--primary)' } : undefined}
                    onClick={() => { setCategory(c.value); setBillerCode(''); setBill(null); setAmount(''); }}>
              <span aria-hidden>{c.icon}</span>{c.label}
            </button>
          ))}
        </div>
        {billers.isLoading ? <Spinner /> : billers.error ? <ErrorBanner error={billers.error} /> : biller && (
          <form className="form" onSubmit={(e) => { e.preventDefault(); if (amountOk && accountOk) pay.mutate(); }}>
            <Select label="Biller" value={biller.code} onChange={(e) => { setBillerCode(e.target.value); setBill(null); }}>
              {billers.data!.map((b) => <option key={b.code} value={b.code}>{b.name}</option>)}
            </Select>
            <TextInput label={biller.accountLabel} value={account} onChange={(e) => { setAccount(e.target.value); setBill(null); }}
                       error={account && !accountOk ? `Enter a valid ${biller.accountLabel.toLowerCase()}` : null} />
            {biller.fetchSupported && (
              <Button type="button" variant="secondary" busy={fetchBill.isPending} disabled={!accountOk} onClick={() => fetchBill.mutate()}>Fetch bill</Button>
            )}
            <ErrorBanner error={fetchBill.error} />
            {bill && (
              <Banner tone="info">
                <span>{bill.customerName} · bill {bill.billNumber} · due {formatDate(bill.dueDate)}</span>
                <strong>{formatMoney(bill.amountDue)}</strong>
              </Banner>
            )}
            <AmountInput value={amount} onChange={setAmount}
                         hint={`Between ${formatMoney(biller.minAmount)} and ${formatMoney(biller.maxAmount)}`}
                         error={amount && !amountOk ? 'Outside this biller’s limits' : null} />
            <ErrorBanner error={pay.error} />
            <Button type="submit" busy={pay.isPending} disabled={!amountOk || !accountOk || (biller.fetchSupported && !bill)}>
              {biller.fetchSupported ? 'Pay bill' : 'Recharge'} {amountOk ? formatMoney(amount) : ''}
            </Button>
            <p className="mock-note">Mock biller: accounts ending in 0000 don't exist; amounts ending in .13 are declined (refunded); .14 times out once.</p>
          </form>
        )}
      </Card>
      <Card title="Cashback"><p className="muted">Bill payments of ₹200 or more earn 1% cashback (up to ₹50), credited automatically.</p></Card>
    </div>
  );
}

function BillHistory() {
  const history = useQuery({ queryKey: ['bills', 'history'], queryFn: () => billApi.history() });
  if (history.isLoading) return <Spinner />;
  if (history.error) return <ErrorBanner error={history.error} />;
  if (!history.data?.items.length) return <Card><Empty title="No bill payments yet" /></Card>;
  return (
    <Card>
      <div className="table-wrap">
        <table>
          <thead><tr><th>When</th><th>Biller</th><th>Account</th><th>Amount</th><th>Status</th></tr></thead>
          <tbody>
            {history.data.items.map((b) => (
              <tr key={b.id}>
                <td className="small">{formatDateTime(b.createdAt)}</td>
                <td>{humanize(b.category)}<div className="small muted">{b.billerCode}{b.source === 'MANDATE' ? ' · auto-pay' : ''}</div></td>
                <td className="mono">{b.accountRefMasked}</td>
                <td><Amount value={b.amount} /></td>
                <td><Badge value={b.status} />{b.refunded && <div className="small muted">refunded</div>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Card>
  );
}

function AutoPay() {
  const mandates = useQuery({ queryKey: ['mandates'], queryFn: billApi.mandates });
  const [creating, setCreating] = useState(false);
  const [runs, setRuns] = useState<string | null>(null);
  const toast = useToast();
  const queryClient = useQueryClient();
  const cancel = useMutation({
    mutationFn: (id: string) => billApi.cancelMandate(id),
    onSuccess: () => { toast('Auto-pay cancelled'); void queryClient.invalidateQueries({ queryKey: ['mandates'] }); },
  });
  const billerNames = useQuery({ queryKey: ['billers', 'names'], queryFn: async () => new Map((await billApi.billers()).map((b) => [b.code, b.name])) });
  return (
    <Card title="Auto-pay" action={<Button onClick={() => setCreating(true)}>Set up auto-pay</Button>}>
      {mandates.isLoading ? <Spinner /> : mandates.error ? <ErrorBanner error={mandates.error} /> : !mandates.data?.length ? (
        <Empty title="No auto-pay yet">Pay bills automatically every week or month, never above your limit.</Empty>
      ) : (
        <ul className="list">
          {mandates.data.map((m) => (
            <li key={m.id} className="list-item">
              <div className="list-main">
                <div className="list-title">{billerNames.data?.get(m.billerCode) ?? m.billerCode} · {m.accountRefMasked}</div>
                <div className="list-sub">{humanize(m.frequency)} · {m.amount ? `${formatMoney(m.amount)} each time` : `bill up to ${formatMoney(m.maxAmount)}`} · next {formatDate(m.nextRunAt)} · {m.runCount} runs</div>
              </div>
              <Badge value={m.status} />
              <Button variant="ghost" onClick={() => setRuns(m.id)}>Runs</Button>
              {m.status === 'ACTIVE' && <Button variant="ghost" busy={cancel.isPending && cancel.variables === m.id} onClick={() => cancel.mutate(m.id)}>Cancel</Button>}
            </li>
          ))}
        </ul>
      )}
      <CreateMandateModal open={creating} onClose={() => setCreating(false)} />
      {runs && <RunsModal id={runs} onClose={() => setRuns(null)} />}
    </Card>
  );
}

function CreateMandateModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [category, setCategory] = useState<BillCategory>('ELECTRICITY');
  const billers = useBillers(category);
  const [code, setCode] = useState('');
  const [account, setAccount] = useState('');
  const [frequency, setFrequency] = useState<MandateFrequency>('MONTHLY');
  const [maxAmount, setMaxAmount] = useState('');
  const [amount, setAmount] = useState('');
  const askPin = usePin();
  const toast = useToast();
  const queryClient = useQueryClient();
  const biller = useMemo(() => billers.data?.find((b) => b.code === code) ?? billers.data?.[0], [billers.data, code]);
  const create = useMutation({
    mutationFn: async () => {
      const pin = await askPin('Authorise auto-pay', <>{biller?.name} · {humanize(frequency)} · up to {formatMoney(maxAmount)}</>);
      if (!pin) return null;
      return billApi.createMandate({ billerCode: biller!.code, accountRef: account, frequency, maxAmount, amount: biller!.fetchSupported ? undefined : amount }, pin);
    },
    onSuccess: (m) => {
      if (!m) return;
      toast('Auto-pay set up');
      void queryClient.invalidateQueries({ queryKey: ['mandates'] });
      onClose();
    },
  });
  const valid = biller && validAccount(biller, account) && isValidAmount(maxAmount) && (biller.fetchSupported || (isValidAmount(amount) && Number(amount) <= Number(maxAmount)));
  return (
    <Modal title="Set up auto-pay" open={open} onClose={onClose}
           footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button busy={create.isPending} disabled={!valid} onClick={() => create.mutate()}>Authorise with PIN</Button></>}>
      <div className="form">
        <Select label="Category" value={category} onChange={(e) => { setCategory(e.target.value as BillCategory); setCode(''); }}>
          {CATEGORIES.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
        </Select>
        {biller && (
          <>
            <Select label="Biller" value={biller.code} onChange={(e) => setCode(e.target.value)}>
              {billers.data!.map((b) => <option key={b.code} value={b.code}>{b.name}</option>)}
            </Select>
            <TextInput label={biller.accountLabel} value={account} onChange={(e) => setAccount(e.target.value)}
                       error={account && !validAccount(biller, account) ? `Enter a valid ${biller.accountLabel.toLowerCase()}` : null} />
            <Select label="Frequency" value={frequency} onChange={(e) => setFrequency(e.target.value as MandateFrequency)}>
              <option value="MONTHLY">Monthly</option><option value="WEEKLY">Weekly</option>
            </Select>
            {!biller.fetchSupported && <AmountInput label="Recharge amount" value={amount} onChange={setAmount}
                                                    error={amount && maxAmount && Number(amount) > Number(maxAmount) ? 'Must not be above the limit below' : null} />}
            <AmountInput label="Never pay more than" value={maxAmount} onChange={setMaxAmount}
                         hint={biller.fetchSupported ? 'Bills above this are skipped and you are notified' : undefined} />
          </>
        )}
        <ErrorBanner error={create.error} />
      </div>
    </Modal>
  );
}

function RunsModal({ id, onClose }: { id: string; onClose: () => void }) {
  const runs = useQuery({ queryKey: ['mandate-runs', id], queryFn: () => billApi.executions(id) });
  return (
    <Modal title="Auto-pay runs" open onClose={onClose}>
      {runs.isLoading ? <Spinner /> : runs.error ? <ErrorBanner error={runs.error} /> : !runs.data?.length ? <Empty title="No runs yet" /> : (
        <ul className="list">
          {runs.data.map((r) => (
            <li key={r.id} className="list-item">
              <div className="list-main"><div className="list-title">{formatDateTime(r.scheduledFor)}</div>{r.reason && <div className="list-sub">{r.reason}</div>}</div>
              {r.amount !== null && <Amount value={r.amount} />} <Badge value={r.status} />
            </li>
          ))}
        </ul>
      )}
    </Modal>
  );
}
