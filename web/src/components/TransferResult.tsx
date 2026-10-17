import { useEffect } from 'react';
import { Link } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { transactionApi } from '../api/endpoints';
import type { TransferResponse } from '../api/types';
import { formatDateTime, formatMoney, humanize } from '../lib/format';
import { Button, KeyValue } from './ui';

/** Outcome of a transfer; while the saga is still PENDING it polls /transfers/{id} until it settles. */
export function TransferResult({ initial, onDone }: { initial: TransferResponse; onDone: () => void }) {
  const queryClient = useQueryClient();
  const pending = initial.status === 'PENDING' || initial.status === 'INITIATED';
  const poll = useQuery({
    queryKey: ['transfer', initial.transactionId],
    queryFn: () => transactionApi.transferStatus(initial.transactionId),
    enabled: pending,
    refetchInterval: (q) => (q.state.data && !['PENDING', 'INITIATED'].includes(q.state.data.status) ? false : 2000),
    initialData: initial,
  });
  const t = poll.data ?? initial;
  const settled = !['PENDING', 'INITIATED'].includes(t.status);

  useEffect(() => {
    if (settled) {
      void queryClient.invalidateQueries({ queryKey: ['wallet'] });
      void queryClient.invalidateQueries({ queryKey: ['transactions'] });
    }
  }, [settled, queryClient]);

  const ok = t.status === 'SUCCESS';
  return (
    <div className="result">
      <div className={`result-icon ${ok ? 'success' : settled ? 'danger' : 'warning'}`} aria-hidden>{ok ? '✓' : settled ? '✕' : '…'}</div>
      <h2>{ok ? 'Payment successful' : settled ? 'Payment failed' : 'Processing payment'}</h2>
      <p className="balance" style={{ fontSize: '2rem' }}>{formatMoney(t.amount)}</p>
      <p className="muted">{ok ? 'Paid to' : 'To'} {t.payee.name ? `${t.payee.name} · ` : ''}{t.payee.ref}</p>
      {!ok && settled && (
        <p className="banner banner-danger" style={{ justifyContent: 'center' }}>
          {t.failureCode === 'TXN_RISK_BLOCKED' ? 'Blocked for your safety. Contact support if this was you.' : humanize(t.failureCode)}
          {t.failureCode && t.failureCode !== 'TXN_RISK_BLOCKED' && ' — any amount debited has been returned.'}
        </p>
      )}
      {!settled && <p className="small muted">This can take a few seconds. You can leave this page; we'll notify you.</p>}
      <div style={{ textAlign: 'left', margin: '18px 0' }}>
        <KeyValue items={[['Transaction ID', <code key="id">{t.transactionId}</code>], ['Note', t.note], ['Time', formatDateTime(t.completedAt ?? t.createdAt)]]} />
      </div>
      <div className="row" style={{ justifyContent: 'center' }}>
        <Button onClick={onDone}>New payment</Button>
        <Link to="/transactions" className="btn btn-secondary">View history</Link>
      </div>
    </div>
  );
}
