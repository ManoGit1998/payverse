import { Link } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { paymentApi, rewardsApi, transactionApi, userApi, walletApi } from '../api/endpoints';
import { Amount, Badge, Banner, Card, Empty, ErrorBanner, PageHeader, Spinner } from '../components/ui';
import { TransactionRow } from '../components/TransactionRow';
import { formatMoney } from '../lib/format';

function Meter({ label, used, limit }: { label: string; used: string; limit: string }) {
  const pct = Number(limit) > 0 ? Math.min(100, (Number(used) / Number(limit)) * 100) : 0;
  return (
    <div>
      <div className="row-between small"><span>{label}</span><span className="muted">{formatMoney(used)} of {formatMoney(limit)}</span></div>
      <div className={`meter ${pct >= 100 ? 'over' : ''}`}><div style={{ width: `${pct}%` }} /></div>
    </div>
  );
}

export default function HomePage() {
  const wallet = useQuery({ queryKey: ['wallet'], queryFn: walletApi.wallet });
  const profile = useQuery({ queryKey: ['profile'], queryFn: userApi.profile });
  const recent = useQuery({ queryKey: ['transactions', 'recent'], queryFn: () => transactionApi.history({ size: 5 }) });
  const requests = useQuery({ queryKey: ['money-requests', 'INCOMING'], queryFn: () => transactionApi.moneyRequests('INCOMING') });
  const collects = useQuery({ queryKey: ['collects', 'INCOMING'], queryFn: () => paymentApi.collects('INCOMING') });
  const rewards = useQuery({ queryKey: ['rewards', 'summary'], queryFn: rewardsApi.summary });
  const vpas = useQuery({ queryKey: ['vpas'], queryFn: paymentApi.myVpas });

  const pendingAsks = (requests.data?.items.filter((r) => r.status === 'PENDING').length ?? 0)
    + (collects.data?.items.filter((c) => c.status === 'PENDING').length ?? 0);
  const name = profile.data?.fullName?.split(' ')[0];
  const vpa = vpas.data?.find((v) => v.defaultVpa)?.vpa ?? vpas.data?.[0]?.vpa;

  return (
    <div className="stack">
      <PageHeader title={name ? `Hi, ${name}` : 'Welcome'} subtitle={vpa ? <>Your UPI ID: <strong>{vpa}</strong></> : undefined} />

      {profile.data?.kycTier === 'NONE' && (
        <Banner tone="warning"><span>Complete minimum KYC to add money and make payments.</span><Link to="/onboarding">Verify now →</Link></Banner>
      )}
      {wallet.data?.status === 'FROZEN' && (
        <Banner tone="danger">Your wallet is frozen{wallet.data.statusReason ? `: ${wallet.data.statusReason}` : ''}. Contact support.</Banner>
      )}
      {pendingAsks > 0 && (
        <Banner tone="info"><span>You have {pendingAsks} payment request{pendingAsks > 1 ? 's' : ''} waiting.</span><Link to="/requests">Review →</Link></Banner>
      )}

      <div className="grid-2">
        <Card className="balance-card">
          {wallet.isLoading ? <Spinner /> : wallet.error ? <ErrorBanner error={wallet.error} onRetry={() => wallet.refetch()} /> : wallet.data && (
            <>
              <div className="row-between">
                <span className="muted">Wallet balance</span>
                <Badge value={wallet.data.kycTier} />
              </div>
              <div className="balance">{formatMoney(wallet.data.balance)}</div>
              <div className="row" style={{ marginTop: 12 }}>
                <Link to="/wallet?tab=add" className="btn btn-secondary">+ Add money</Link>
                <Link to="/wallet?tab=withdraw" className="btn btn-ghost" style={{ color: '#fff' }}>Withdraw</Link>
              </div>
            </>
          )}
        </Card>
        <Card title="Limits">
          {wallet.data ? (
            <div className="stack">
              <Meter label="Added this month" used={wallet.data.limits.monthlyLoadUsed} limit={wallet.data.limits.monthlyLoadLimit} />
              <Meter label="Spent today" used={wallet.data.limits.dailyDebitUsed} limit={wallet.data.limits.dailyDebitLimit} />
              <p className="small muted">Maximum balance {formatMoney(wallet.data.limits.maxBalance)} on your KYC tier.</p>
            </div>
          ) : <Spinner />}
        </Card>
      </div>

      <div className="quick-actions">
        {[
          ['/pay', '💸', 'Pay'], ['/pay?mode=QR', '📷', 'Scan & pay'], ['/requests?new=1', '🙋', 'Request'], ['/bills', '📱', 'Recharge'],
          ['/split', '🍕', 'Split bill'], ['/rewards', '🎁', 'Rewards'],
        ].map(([to, icon, label]) => (
          <Link key={to} to={to} className="quick-action"><span aria-hidden>{icon}</span>{label}</Link>
        ))}
      </div>

      <div className="grid-2">
        <Card title="Recent activity" action={<Link to="/transactions" className="small">See all</Link>}>
          {recent.isLoading ? <Spinner /> : recent.error ? <ErrorBanner error={recent.error} /> : recent.data?.items.length ? (
            <ul className="list">{recent.data.items.map((t) => <TransactionRow key={t.id} t={t} />)}</ul>
          ) : <Empty title="No payments yet">Your transfers will appear here.</Empty>}
        </Card>
        <Card title="Rewards" action={<Link to="/rewards" className="small">Open</Link>}>
          {rewards.data ? (
            <div className="stack">
              <div><span className="muted small">Cashback earned</span><div className="balance" style={{ fontSize: '1.8rem' }}><Amount value={rewards.data.totalCredited} /></div></div>
              {Number(rewards.data.pendingCredit.amount) > 0 && <p className="small muted">{formatMoney(rewards.data.pendingCredit)} on its way to your wallet.</p>}
              <p className="small">Pay ₹100 or more to friends to win scratch cards.</p>
            </div>
          ) : <Spinner />}
        </Card>
      </div>
    </div>
  );
}
