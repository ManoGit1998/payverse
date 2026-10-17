import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { paymentApi, transactionApi } from '../api/endpoints';
import { errorMessage } from '../api/errors';
import type { Collect, MoneyRequest, PayeeType } from '../api/types';
import { usePin, useToast } from '../components/feedback';
import { Amount, AmountInput, Badge, Button, Card, Empty, ErrorBanner, Modal, PageHeader, Select, Spinner, Tabs, TextInput } from '../components/ui';
import { formatMoney, isValidAmount, relativeTime } from '../lib/format';
import { useIdempotencyKey } from '../lib/useIdempotencyKey';

type Tab = 'INCOMING' | 'OUTGOING';

export default function RequestsPage() {
  const [params] = useSearchParams();
  const [tab, setTab] = useState<Tab>('INCOMING');
  const [creating, setCreating] = useState<'request' | 'collect' | null>(params.get('new') ? 'request' : null);
  const queryClient = useQueryClient();
  const toast = useToast();
  const askPin = usePin();
  const idempotency = useIdempotencyKey();

  const requests = useQuery({ queryKey: ['money-requests', tab], queryFn: () => transactionApi.moneyRequests(tab) });
  const collects = useQuery({ queryKey: ['collects', tab], queryFn: () => paymentApi.collects(tab) });
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['money-requests'] });
    void queryClient.invalidateQueries({ queryKey: ['collects'] });
    void queryClient.invalidateQueries({ queryKey: ['wallet'] });
  };

  const act = useMutation({
    mutationFn: async (action: () => Promise<unknown>) => action(),
    onSuccess: refresh,
    onError: (e) => toast(errorMessage(e), 'danger'),
  });

  const payRequest = async (r: MoneyRequest) => {
    const pin = await askPin(`Pay ${formatMoney(r.amount)}`, <>to <strong>{r.counterparty}</strong>{r.note ? ` · ${r.note}` : ''}</>);
    if (!pin) return;
    act.mutate(async () => {
      const t = await transactionApi.payRequest(r.id, pin, idempotency.keyFor({ request: r.id }));
      idempotency.reset();
      toast(t.status === 'SUCCESS' ? `Paid ${formatMoney(r.amount)} to ${r.counterparty}` : `Payment ${t.status.toLowerCase()}`, t.status === 'FAILED' ? 'danger' : 'success');
    });
  };

  const approveCollect = async (c: Collect) => {
    const pin = await askPin(`Approve ${formatMoney(c.amount)}`, <>requested by <strong>{c.payeeVpa}</strong>{c.note ? ` · ${c.note}` : ''}</>);
    if (!pin) return;
    act.mutate(async () => {
      await paymentApi.approveCollect(c.id, pin, idempotency.keyFor({ collect: c.id }));
      idempotency.reset();
      toast(`Approved ${formatMoney(c.amount)} to ${c.payeeVpa}`);
    });
  };

  const moneyRequests = requests.data?.items ?? [];
  const upiCollects = collects.data?.items ?? [];

  return (
    <div className="stack">
      <PageHeader title="Requests" subtitle="Money requests from PayVerse users and UPI collect requests"
                  action={<div className="row"><Button onClick={() => setCreating('request')}>Request money</Button><Button variant="secondary" onClick={() => setCreating('collect')}>UPI collect</Button></div>} />
      <Tabs<Tab> value={tab} onChange={setTab} options={[{ value: 'INCOMING', label: 'Asked of me' }, { value: 'OUTGOING', label: 'I asked' }]} />
      <div className="grid-2">
        <Card title="Money requests">
          {requests.isLoading ? <Spinner /> : requests.error ? <ErrorBanner error={requests.error} /> : moneyRequests.length === 0 ? (
            <Empty title="Nothing here">{tab === 'INCOMING' ? 'When someone requests money from you, it shows up here.' : 'Requests you send appear here.'}</Empty>
          ) : (
            <ul className="list">
              {moneyRequests.map((r) => (
                <li key={r.id} className="list-item">
                  <div className="list-main">
                    <div className="list-title">{tab === 'INCOMING' ? r.counterparty : `To ${r.counterparty}`}</div>
                    <div className="list-sub">{r.note ? `${r.note} · ` : ''}{relativeTime(r.createdAt)}{r.splitBillId ? ' · split bill' : ''}</div>
                  </div>
                  <div className="stack" style={{ alignItems: 'flex-end', gap: 6 }}>
                    <Amount value={r.amount} />
                    {r.status === 'PENDING' && tab === 'INCOMING' ? (
                      <div className="row">
                        <Button variant="ghost" onClick={() => act.mutate(() => transactionApi.declineRequest(r.id))}>Decline</Button>
                        <Button onClick={() => void payRequest(r)}>Pay</Button>
                      </div>
                    ) : r.status === 'PENDING' ? (
                      <Button variant="ghost" onClick={() => act.mutate(() => transactionApi.cancelRequest(r.id))}>Cancel</Button>
                    ) : <Badge value={r.status} />}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Card>
        <Card title="UPI collect requests">
          {collects.isLoading ? <Spinner /> : collects.error ? <ErrorBanner error={collects.error} /> : upiCollects.length === 0 ? (
            <Empty title="No collect requests" />
          ) : (
            <ul className="list">
              {upiCollects.map((c) => (
                <li key={c.id} className="list-item">
                  <div className="list-main">
                    <div className="list-title">{tab === 'INCOMING' ? c.payeeVpa : `To ${c.payerVpa}`}</div>
                    <div className="list-sub">{c.note ? `${c.note} · ` : ''}expires {relativeTime(c.expiresAt).replace(' ago', '')}</div>
                  </div>
                  <div className="stack" style={{ alignItems: 'flex-end', gap: 6 }}>
                    <Amount value={c.amount} />
                    {c.status === 'PENDING' && tab === 'INCOMING' ? (
                      <div className="row">
                        <Button variant="ghost" onClick={() => act.mutate(() => paymentApi.rejectCollect(c.id))}>Reject</Button>
                        <Button onClick={() => void approveCollect(c)}>Approve</Button>
                      </div>
                    ) : <Badge value={c.status} />}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>
      <CreateRequestModal kind={creating} onClose={() => setCreating(null)} onCreated={() => { setCreating(null); setTab('OUTGOING'); refresh(); }} />
    </div>
  );
}

function CreateRequestModal({ kind, onClose, onCreated }: { kind: 'request' | 'collect' | null; onClose: () => void; onCreated: () => void }) {
  const [type, setType] = useState<PayeeType>('VPA');
  const [who, setWho] = useState('');
  const [amount, setAmount] = useState('');
  const [note, setNote] = useState('');
  const toast = useToast();
  const create = useMutation({
    mutationFn: (): Promise<unknown> => kind === 'collect'
      ? paymentApi.createCollect(who.trim().toLowerCase(), amount, note)
      : transactionApi.requestMoney({ type, value: type === 'VPA' ? who.trim().toLowerCase() : who.trim() }, amount, note),
    onSuccess: () => {
      toast(`Request for ${formatMoney(amount)} sent`);
      setWho('');
      setAmount('');
      setNote('');
      onCreated();
    },
  });
  const valid = isValidAmount(amount) && (type === 'VPA' || kind === 'collect' ? who.includes('@') : /^[6-9]\d{9}$/.test(who));
  return (
    <Modal title={kind === 'collect' ? 'Send a UPI collect request' : 'Request money'} open={kind !== null} onClose={onClose}
           footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button busy={create.isPending} disabled={!valid} onClick={() => create.mutate()}>Send request</Button></>}>
      <div className="form">
        {kind === 'request' && (
          <Select label="From" value={type} onChange={(e) => setType(e.target.value as PayeeType)}>
            <option value="VPA">UPI ID</option>
            <option value="MOBILE">Mobile number</option>
          </Select>
        )}
        <TextInput label={kind === 'collect' || type === 'VPA' ? 'Payer UPI ID' : 'Payer mobile'} value={who} onChange={(e) => setWho(e.target.value)}
                   placeholder={kind === 'collect' || type === 'VPA' ? 'friend@payverse' : '98765 43210'} />
        <AmountInput value={amount} onChange={setAmount} />
        <TextInput label="Note" value={note} maxLength={100} onChange={(e) => setNote(e.target.value)} />
        <ErrorBanner error={create.error} />
      </div>
    </Modal>
  );
}
