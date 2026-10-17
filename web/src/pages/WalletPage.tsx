import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { userApi, walletApi } from '../api/endpoints';
import type { PaymentMethod } from '../api/types';
import { usePin, useToast } from '../components/feedback';
import { Amount, AmountInput, Banner, Button, Card, Empty, ErrorBanner, PageHeader, Pager, Select, Spinner, Tabs } from '../components/ui';
import { formatDateTime, formatMoney, humanize, isValidAmount } from '../lib/format';
import { useIdempotencyKey } from '../lib/useIdempotencyKey';

type Tab = 'add' | 'withdraw' | 'ledger';

export default function WalletPage() {
  const [params, setParams] = useSearchParams();
  const tab = (params.get('tab') as Tab) || 'add';
  const wallet = useQuery({ queryKey: ['wallet'], queryFn: walletApi.wallet });
  return (
    <div className="stack">
      <PageHeader title="Wallet" subtitle={wallet.data ? <>Balance <strong>{formatMoney(wallet.data.balance)}</strong> · {humanize(wallet.data.kycTier)}</> : undefined} />
      {wallet.data?.status === 'FROZEN' && <Banner tone="danger">Wallet frozen{wallet.data.statusReason ? `: ${wallet.data.statusReason}` : ''}.</Banner>}
      <Tabs<Tab> value={tab} onChange={(t) => setParams({ tab: t })} options={[{ value: 'add', label: 'Add money' }, { value: 'withdraw', label: 'Withdraw to bank' }, { value: 'ledger', label: 'Ledger' }]} />
      {tab === 'add' && <AddMoney />}
      {tab === 'withdraw' && <Withdraw />}
      {tab === 'ledger' && <Ledger />}
    </div>
  );
}

function AddMoney() {
  const [amount, setAmount] = useState('');
  const [method, setMethod] = useState<PaymentMethod>('UPI');
  const queryClient = useQueryClient();
  const toast = useToast();
  const idempotency = useIdempotencyKey();
  const wallet = useQuery({ queryKey: ['wallet'], queryFn: walletApi.wallet });
  const add = useMutation({
    mutationFn: () => walletApi.addMoney(amount, method, idempotency.keyFor({ amount, method })),
    onSuccess: (r) => {
      idempotency.reset();
      toast(`${formatMoney(r.amount)} added · new balance ${formatMoney(r.balance)}`);
      setAmount('');
      void queryClient.invalidateQueries({ queryKey: ['wallet'] });
    },
  });
  const room = wallet.data ? Math.min(
    Number(wallet.data.limits.maxBalance) - Number(wallet.data.balance.amount),
    Number(wallet.data.limits.monthlyLoadLimit) - Number(wallet.data.limits.monthlyLoadUsed)) : null;
  return (
    <div className="grid-2">
      <Card title="Add money">
        <form className="form" onSubmit={(e) => { e.preventDefault(); add.mutate(); }}>
          <AmountInput value={amount} onChange={setAmount} autoFocus hint={room !== null ? `You can add up to ${formatMoney(Math.max(0, room))} on your KYC tier` : undefined}
                       error={room !== null && isValidAmount(amount) && Number(amount) > room ? 'Above your wallet limit' : null} />
          <div className="row">
            {['500', '1000', '2000'].map((v) => <Button key={v} type="button" variant="secondary" onClick={() => setAmount(v)}>+{formatMoney(v).replace('.00', '')}</Button>)}
          </div>
          <Select label="Pay with" value={method} onChange={(e) => setMethod(e.target.value as PaymentMethod)}>
            <option value="UPI">UPI</option>
            <option value="CARD">Debit / credit card</option>
            <option value="NETBANKING">Net banking</option>
          </Select>
          <ErrorBanner error={add.error} />
          <Button type="submit" busy={add.isPending} disabled={!isValidAmount(amount)}>Add {isValidAmount(amount) ? formatMoney(amount) : 'money'}</Button>
          <p className="mock-note">Mock payment gateway: amounts ending in .13 are declined.</p>
        </form>
      </Card>
      <Card title="How it works"><p className="muted">Money is charged through the payment gateway and credited to your wallet ledger in the same step. Retrying a request that timed out never charges twice.</p></Card>
    </div>
  );
}

function Withdraw() {
  const [amount, setAmount] = useState('');
  const [account, setAccount] = useState('');
  const queryClient = useQueryClient();
  const toast = useToast();
  const askPin = usePin();
  const idempotency = useIdempotencyKey();
  const accounts = useQuery({ queryKey: ['bank-accounts'], queryFn: userApi.bankAccounts });
  const selected = account || accounts.data?.find((a) => a.primary)?.id || accounts.data?.[0]?.id || '';
  const withdraw = useMutation({
    mutationFn: async () => {
      const target = accounts.data?.find((a) => a.id === selected);
      const pin = await askPin(`Withdraw ${formatMoney(amount)}`, <>to {target?.holderName} · {target?.maskedAccountNumber}</>);
      if (!pin) return null;
      return walletApi.withdraw(amount, selected, pin, idempotency.keyFor({ amount, selected }));
    },
    onSuccess: (r) => {
      if (!r) return;
      idempotency.reset();
      toast(`${formatMoney(r.amount)} is on its way to ${r.maskedAccountNumber}`);
      setAmount('');
      void queryClient.invalidateQueries({ queryKey: ['wallet'] });
    },
  });
  if (accounts.isLoading) return <Spinner />;
  if (!accounts.data?.length) return <Card><Empty title="No bank account linked"><Link to="/profile?tab=banks">Link a bank account</Link> to withdraw.</Empty></Card>;
  return (
    <Card title="Withdraw to bank">
      <form className="form" onSubmit={(e) => { e.preventDefault(); withdraw.mutate(); }} style={{ maxWidth: 460 }}>
        <Select label="To" value={selected} onChange={(e) => setAccount(e.target.value)}>
          {accounts.data.map((a) => <option key={a.id} value={a.id}>{a.holderName} · {a.maskedAccountNumber} ({a.ifsc}){a.primary ? ' · primary' : ''}</option>)}
        </Select>
        <AmountInput value={amount} onChange={setAmount} />
        <ErrorBanner error={withdraw.error} />
        <Button type="submit" busy={withdraw.isPending} disabled={!isValidAmount(amount) || !selected}>Withdraw</Button>
        <p className="mock-note">Mock payout: amounts ending in .14 fail and are rolled back.</p>
      </form>
    </Card>
  );
}

function Ledger() {
  const [page, setPage] = useState(0);
  const ledger = useQuery({ queryKey: ['ledger', page], queryFn: () => walletApi.ledger(page, 20) });
  return (
    <Card title="Ledger" action={<span className="small muted">Every entry of your wallet account, with the running balance</span>}>
      {ledger.isLoading ? <Spinner /> : ledger.error ? <ErrorBanner error={ledger.error} /> : !ledger.data?.items.length ? <Empty title="No entries yet" /> : (
        <>
          <div className="table-wrap">
            <table>
              <thead><tr><th>When</th><th>Type</th><th>Description</th><th style={{ textAlign: 'right' }}>Amount</th><th style={{ textAlign: 'right' }}>Balance</th></tr></thead>
              <tbody>
                {ledger.data.items.map((e) => (
                  <tr key={e.entryId}>
                    <td className="small">{formatDateTime(e.createdAt)}</td>
                    <td>{humanize(e.type)}</td>
                    <td className="small muted">{e.description ?? e.referenceType}</td>
                    <td style={{ textAlign: 'right' }}><Amount value={e.amount} sign={e.side === 'CREDIT' ? '+' : '-'} /></td>
                    <td style={{ textAlign: 'right' }} className="amount">{formatMoney(e.balanceAfter)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pager page={page} totalPages={ledger.data.totalPages} onChange={setPage} />
        </>
      )}
    </Card>
  );
}
