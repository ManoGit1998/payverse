import type { Transaction } from '../api/types';
import { initials, relativeTime } from '../lib/format';
import { Amount, Badge } from './ui';

export function TransactionRow({ t, onClick }: { t: Transaction; onClick?: () => void }) {
  const sent = t.direction === 'SENT';
  return (
    <li className={`list-item ${onClick ? 'clickable' : ''}`} onClick={onClick} onKeyDown={(e) => e.key === 'Enter' && onClick?.()} tabIndex={onClick ? 0 : undefined}>
      <span className="avatar" aria-hidden>{initials(t.counterparty)}</span>
      <div className="list-main">
        <div className="list-title">{sent ? 'To ' : 'From '}{t.counterparty}</div>
        <div className="list-sub">{relativeTime(t.createdAt)}{t.note ? ` · ${t.note}` : ''}</div>
      </div>
      <div style={{ textAlign: 'right' }}>
        <Amount value={t.amount} sign={sent ? '-' : '+'} />
        {t.status !== 'SUCCESS' && <div><Badge value={t.status} /></div>}
      </div>
    </li>
  );
}
