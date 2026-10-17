import { Link } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { disputeApi } from '../api/endpoints';
import { Badge, Card, Empty, ErrorBanner, PageHeader, Spinner } from '../components/ui';
import { formatDateTime, humanize } from '../lib/format';

export default function DisputesPage() {
  const disputes = useQuery({ queryKey: ['disputes'], queryFn: disputeApi.mine });
  return (
    <div className="stack">
      <PageHeader title="Help & disputes" subtitle="Problems with a payment? Open it in History and choose “Report a problem”." />
      <Card title="My disputes">
        {disputes.isLoading ? <Spinner /> : disputes.error ? <ErrorBanner error={disputes.error} /> : !disputes.data?.length ? (
          <Empty title="No disputes">Raise one from a transaction in <Link to="/transactions">History</Link>.</Empty>
        ) : (
          <ul className="list">
            {disputes.data.map((d) => (
              <li key={d.id} className="list-item">
                <div className="list-main">
                  <div className="list-title">{d.reason}</div>
                  <div className="list-sub">Opened {formatDateTime(d.createdAt)} · transaction <code>{d.transactionId.slice(0, 8)}</code>
                    {d.outcome && ` · ${d.outcome === 'REFUND' ? 'refund approved for processing' : 'not upheld'}`}{d.resolutionNote ? ` — ${d.resolutionNote}` : ''}</div>
                </div>
                <Badge value={d.status} />{d.outcome && <Badge value={d.outcome} tone={d.outcome === 'REFUND' ? 'success' : 'neutral'} />}
                <span className="sr-only">{humanize(d.status)}</span>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </div>
  );
}
