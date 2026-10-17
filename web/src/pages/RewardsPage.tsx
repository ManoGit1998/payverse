import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { rewardsApi } from '../api/endpoints';
import { errorMessage } from '../api/errors';
import type { ScratchCard } from '../api/types';
import { useToast } from '../components/feedback';
import { Amount, Badge, Button, Card, Empty, ErrorBanner, PageHeader, Spinner, TextInput } from '../components/ui';
import { formatDate, formatMoney, humanize, relativeTime } from '../lib/format';

export default function RewardsPage() {
  const summary = useQuery({ queryKey: ['rewards', 'summary'], queryFn: rewardsApi.summary });
  const cards = useQuery({ queryKey: ['rewards', 'cards'], queryFn: rewardsApi.scratchCards });
  const coupons = useQuery({ queryKey: ['rewards', 'coupons'], queryFn: rewardsApi.coupons });
  const referral = useQuery({ queryKey: ['rewards', 'referral'], queryFn: rewardsApi.referral });
  const history = useQuery({ queryKey: ['rewards', 'history'], queryFn: () => rewardsApi.history() });
  const [code, setCode] = useState('');
  const toast = useToast();
  const queryClient = useQueryClient();
  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['rewards'] });

  const scratch = useMutation({
    mutationFn: (id: string) => rewardsApi.scratch(id),
    onSuccess: (c) => { toast(`You won ${formatMoney(c.amount)}! It's on its way to your wallet.`); refresh(); },
    onError: (e) => toast(errorMessage(e), 'danger'),
  });
  const redeem = useMutation({
    mutationFn: (c: string) => rewardsApi.redeem(c),
    onSuccess: (r) => { toast(`${formatMoney(r.amount)} cashback is on its way`); setCode(''); refresh(); },
  });

  const shareLink = referral.data ? `${window.location.origin}/login?ref=${referral.data.code}` : '';

  return (
    <div className="stack">
      <PageHeader title="Rewards" subtitle="Cashback is credited straight to your wallet" />
      <div className="grid">
        <Card title="Cashback earned">
          {summary.data ? (
            <>
              <div className="balance"><Amount value={summary.data.totalCredited} /></div>
              {Number(summary.data.pendingCredit.amount) > 0 && <p className="small muted">{formatMoney(summary.data.pendingCredit)} pending</p>}
            </>
          ) : <Spinner />}
        </Card>
        <Card title="Invite friends">
          {referral.data ? (
            <div className="stack">
              <p className="small muted">Your friend gets {formatMoney(referral.data.refereeBonus)} and you get {formatMoney(referral.data.referrerBonus)} once they complete KYC.</p>
              <div className="copy-box">{referral.data.code}
                <button className="link" style={{ marginLeft: 'auto' }} onClick={() => { void navigator.clipboard?.writeText(shareLink); toast('Invite link copied'); }}>Copy link</button>
              </div>
              <p className="small">{referral.data.referred} joined · {referral.data.rewarded} rewarded</p>
            </div>
          ) : <Spinner />}
        </Card>
      </div>

      <Card title="Scratch cards" action={<span className="small muted">Win one for every payment of ₹100+ to a friend (3 a day)</span>}>
        {cards.isLoading ? <Spinner /> : cards.error ? <ErrorBanner error={cards.error} /> : !cards.data?.items.length ? <Empty title="No scratch cards yet" /> : (
          <div className="scratch-grid">
            {cards.data.items.map((c) => <ScratchTile key={c.id} card={c} busy={scratch.isPending && scratch.variables === c.id} onScratch={() => scratch.mutate(c.id)} />)}
          </div>
        )}
      </Card>

      <div className="grid-2">
        <Card title="Coupons">
          <form className="row" style={{ alignItems: 'flex-end', marginBottom: 12 }} onSubmit={(e) => { e.preventDefault(); if (code.trim()) redeem.mutate(code.trim()); }}>
            <div style={{ flex: 1 }}><TextInput label="Have a code?" value={code} onChange={(e) => setCode(e.target.value.toUpperCase())} placeholder="PAYVERSE10" /></div>
            <Button type="submit" busy={redeem.isPending} disabled={!code.trim()}>Redeem</Button>
          </form>
          <ErrorBanner error={redeem.error} />
          {coupons.data?.length ? (
            <ul className="list">
              {coupons.data.map((c) => (
                <li key={c.id} className="list-item">
                  <div className="list-main"><div className="list-title">{c.code} · {formatMoney(c.cashback)}</div><div className="list-sub">{c.description} · until {formatDate(c.validUntil)}</div></div>
                  <Button variant="secondary" busy={redeem.isPending && redeem.variables === c.code} onClick={() => redeem.mutate(c.code)}>Redeem</Button>
                </li>
              ))}
            </ul>
          ) : coupons.isLoading ? <Spinner /> : <p className="muted small">No coupons available right now.</p>}
        </Card>
        <Card title="Reward history">
          {history.data?.items.length ? (
            <ul className="list">
              {history.data.items.map((r) => (
                <li key={r.id} className="list-item">
                  <div className="list-main"><div className="list-title">{humanize(r.ruleCode)}</div><div className="list-sub">{humanize(r.sourceType)} · {relativeTime(r.createdAt)}</div></div>
                  <Amount value={r.amount} sign="+" /> <Badge value={r.status} />
                </li>
              ))}
            </ul>
          ) : history.isLoading ? <Spinner /> : <p className="muted small">Your cashback will be listed here.</p>}
        </Card>
      </div>
    </div>
  );
}

function ScratchTile({ card, busy, onScratch }: { card: ScratchCard; busy: boolean; onScratch: () => void }) {
  if (card.status === 'SCRATCHED') return <div className="scratch revealed"><span>You won</span><strong>{formatMoney(card.amount)}</strong></div>;
  if (card.status !== 'UNSCRATCHED') return <div className="scratch dead"><Badge value={card.status} /><span className="small">{humanize(card.ruleCode)}</span></div>;
  return (
    <button className="scratch hidden" onClick={onScratch} disabled={busy} aria-label="Scratch card">
      <span style={{ fontSize: '1.6rem' }}>✨</span>
      <span>{busy ? 'Scratching…' : 'Tap to scratch'}</span>
      <span className="small">expires {formatDate(card.expiresAt)}</span>
    </button>
  );
}
