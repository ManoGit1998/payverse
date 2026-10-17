import { useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { transactionApi } from '../api/endpoints';
import type { Payee, PayeeType } from '../api/types';
import { Amount, AmountInput, Badge, Button, Card, ErrorBanner, PageHeader, Spinner, TextInput } from '../components/ui';
import { formatMoney, isValidAmount } from '../lib/format';

/** Creates money requests for each participant (exact shares; the creator's own share comes first). */
export default function SplitPage() {
  const [total, setTotal] = useState('');
  const [note, setNote] = useState('');
  const [includeMe, setIncludeMe] = useState(true);
  const [people, setPeople] = useState<{ type: PayeeType; value: string }[]>([{ type: 'VPA', value: '' }]);
  const [splitId, setSplitId] = useState<string | null>(null);

  const create = useMutation({
    mutationFn: () => transactionApi.createSplit(total, note, people.map((p): Payee => ({ type: p.type, value: p.value.trim().toLowerCase() })), includeMe),
    onSuccess: (s) => setSplitId(s.id),
  });
  const split = useQuery({ queryKey: ['split', splitId], queryFn: () => transactionApi.split(splitId!), enabled: Boolean(splitId), refetchInterval: 10_000 });

  const valid = isValidAmount(total) && people.length > 0 && people.every((p) => (p.type === 'VPA' ? /@/.test(p.value) : /^[6-9]\d{9}$/.test(p.value)));
  const shareCount = people.length + (includeMe ? 1 : 0);
  const estimate = isValidAmount(total) ? Number(total) / shareCount : 0;

  return (
    <div className="stack">
      <PageHeader title="Split a bill" subtitle="Everyone gets a money request for their share" />
      <div className="grid-2">
        <Card title="New split">
          <form className="form" onSubmit={(e) => { e.preventDefault(); if (valid) create.mutate(); }}>
            <AmountInput label="Total" value={total} onChange={setTotal} />
            <TextInput label="What for?" value={note} maxLength={100} placeholder="Goa trip, dinner…" onChange={(e) => setNote(e.target.value)} />
            <label className="checkbox"><input type="checkbox" checked={includeMe} onChange={(e) => setIncludeMe(e.target.checked)} /> Include my share</label>
            {people.map((p, i) => (
              <div key={i} className="row" style={{ alignItems: 'flex-end' }}>
                <select className="input" style={{ width: 110 }} aria-label="Type" value={p.type}
                        onChange={(e) => setPeople(people.map((x, j) => (j === i ? { ...x, type: e.target.value as PayeeType } : x)))}>
                  <option value="VPA">UPI ID</option>
                  <option value="MOBILE">Mobile</option>
                </select>
                <input className="input" style={{ flex: 1 }} aria-label={`Participant ${i + 1}`} value={p.value}
                       placeholder={p.type === 'VPA' ? 'friend@payverse' : '98765 43210'}
                       onChange={(e) => setPeople(people.map((x, j) => (j === i ? { ...x, value: e.target.value } : x)))} />
                <button type="button" className="icon-btn" aria-label="Remove participant" disabled={people.length === 1}
                        onClick={() => setPeople(people.filter((_, j) => j !== i))}>✕</button>
              </div>
            ))}
            <Button type="button" variant="ghost" disabled={people.length >= 20} onClick={() => setPeople([...people, { type: 'VPA', value: '' }])}>+ Add person</Button>
            {estimate > 0 && <p className="small muted">About {formatMoney(estimate)} each across {shareCount} people (exact paise are distributed fairly).</p>}
            <ErrorBanner error={create.error} />
            <Button type="submit" busy={create.isPending} disabled={!valid}>Create split</Button>
          </form>
        </Card>
        <Card title="Split status">
          {!splitId ? <p className="muted">Create a split to track who has paid.</p> : split.isLoading ? <Spinner /> : split.error ? <ErrorBanner error={split.error} /> : split.data && (
            <div className="stack">
              <div className="row-between"><span>{split.data.note ?? 'Split'}</span><Amount value={split.data.total} /></div>
              <p className="small muted">Your share {formatMoney(split.data.creatorShare)} · collected {formatMoney(split.data.collected)}</p>
              <ul className="list">
                {split.data.shares.map((s) => (
                  <li key={s.moneyRequestId} className="list-item">
                    <div className="list-main"><div className="list-title">{s.participant}</div></div>
                    <Amount value={s.amount} /> <Badge value={s.status} />
                  </li>
                ))}
              </ul>
            </div>
          )}
        </Card>
      </div>
    </div>
  );
}
