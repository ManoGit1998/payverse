import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { disputeApi, transactionApi, type HistoryFilter } from '../api/endpoints';
import type { TransactionStatus } from '../api/types';
import { useToast } from '../components/feedback';
import { TransactionRow } from '../components/TransactionRow';
import { Amount, Badge, Button, Card, Empty, ErrorBanner, KeyValue, Modal, PageHeader, Pager, Select, Spinner, TextInput } from '../components/ui';
import { formatDateTime, humanize } from '../lib/format';

export default function TransactionsPage() {
  const [filter, setFilter] = useState<HistoryFilter>({ direction: '', status: '', page: 0, size: 20 });
  const [selected, setSelected] = useState<string | null>(null);
  const [statementOpen, setStatementOpen] = useState(false);
  const query = { ...filter, from: filter.from ? new Date(filter.from).toISOString() : undefined, to: filter.to ? new Date(`${filter.to}T23:59:59`).toISOString() : undefined };
  const history = useQuery({ queryKey: ['transactions', query], queryFn: () => transactionApi.history(query) });
  const set = (patch: Partial<HistoryFilter>) => setFilter((f) => ({ ...f, ...patch, page: patch.page ?? 0 }));

  return (
    <div className="stack">
      <PageHeader title="History" subtitle="Transfers you sent and received" action={<Button variant="secondary" onClick={() => setStatementOpen(true)}>⬇ Statement</Button>} />
      <Card>
        <div className="form-row">
          <Select label="Direction" value={filter.direction} onChange={(e) => set({ direction: e.target.value as HistoryFilter['direction'] })}>
            <option value="">All</option><option value="SENT">Sent</option><option value="RECEIVED">Received</option>
          </Select>
          <Select label="Status" value={filter.status} onChange={(e) => set({ status: e.target.value as TransactionStatus | '' })}>
            <option value="">All</option>
            {['SUCCESS', 'PENDING', 'FAILED', 'REVERSED'].map((s) => <option key={s} value={s}>{humanize(s)}</option>)}
          </Select>
          <TextInput label="From" type="date" value={filter.from ?? ''} onChange={(e) => set({ from: e.target.value || undefined })} />
          <TextInput label="To" type="date" value={filter.to ?? ''} onChange={(e) => set({ to: e.target.value || undefined })} />
          <TextInput label="Min ₹" inputMode="decimal" value={filter.minAmount ?? ''} onChange={(e) => set({ minAmount: e.target.value || undefined })} />
          <TextInput label="Max ₹" inputMode="decimal" value={filter.maxAmount ?? ''} onChange={(e) => set({ maxAmount: e.target.value || undefined })} />
        </div>
      </Card>
      <Card>
        {history.isLoading ? <Spinner /> : history.error ? <ErrorBanner error={history.error} onRetry={() => history.refetch()} /> : !history.data?.items.length ? (
          <Empty title="No transactions match" />
        ) : (
          <>
            <ul className="list">{history.data.items.map((t) => <TransactionRow key={t.id} t={t} onClick={() => setSelected(t.id)} />)}</ul>
            <Pager page={filter.page ?? 0} totalPages={history.data.totalPages} onChange={(p) => set({ page: p })} />
          </>
        )}
      </Card>
      {selected && <TransactionDetailModal id={selected} onClose={() => setSelected(null)} />}
      <StatementModal open={statementOpen} onClose={() => setStatementOpen(false)} />
    </div>
  );
}

function TransactionDetailModal({ id, onClose }: { id: string; onClose: () => void }) {
  const detail = useQuery({ queryKey: ['transaction', id], queryFn: () => transactionApi.detail(id) });
  const [disputing, setDisputing] = useState(false);
  const [reason, setReason] = useState('');
  const toast = useToast();
  const queryClient = useQueryClient();
  const dispute = useMutation({
    mutationFn: () => disputeApi.open(id, reason),
    onSuccess: () => {
      toast('Dispute raised — our support team will review it');
      setDisputing(false);
      void queryClient.invalidateQueries({ queryKey: ['disputes'] });
    },
  });
  const t = detail.data?.transaction;
  return (
    <Modal title="Transaction" open onClose={onClose}>
      {detail.isLoading ? <Spinner /> : detail.error ? <ErrorBanner error={detail.error} /> : t && (
        <div className="stack">
          <div className="row-between">
            <div><div className="muted small">{t.direction === 'SENT' ? 'Paid to' : 'Received from'}</div><strong>{t.counterparty}</strong></div>
            <div style={{ textAlign: 'right' }}><Amount value={t.amount} sign={t.direction === 'SENT' ? '-' : '+'} className="balance" /><div><Badge value={t.status} /></div></div>
          </div>
          <KeyValue items={[
            ['Note', t.note], ['Via', humanize(t.payeeType)], ['Source', humanize(t.source)],
            ['Created', formatDateTime(t.createdAt)], ['Completed', formatDateTime(t.completedAt)],
            ['Failure', t.failureCode ? humanize(t.failureCode) : null], ['ID', <code key="id" className="small">{t.id}</code>],
          ]} />
          <div>
            <h3 className="small muted" style={{ marginBottom: 8 }}>STATUS HISTORY</h3>
            <ul className="timeline">
              {detail.data!.statusHistory.map((s, i) => (
                <li key={i}><strong>{humanize(s.to)}</strong> <span className="small muted">{formatDateTime(s.at)}</span>{s.reason && <div className="small muted">{s.reason}</div>}</li>
              ))}
            </ul>
          </div>
          {t.status === 'SUCCESS' && !disputing && <Button variant="secondary" onClick={() => setDisputing(true)}>Report a problem</Button>}
          {disputing && (
            <div className="form">
              <TextInput label="What went wrong?" value={reason} maxLength={500} autoFocus onChange={(e) => setReason(e.target.value)} placeholder="I paid the wrong person…" />
              <ErrorBanner error={dispute.error} />
              <div className="row"><Button busy={dispute.isPending} disabled={reason.trim().length < 5} onClick={() => dispute.mutate()}>Raise dispute</Button><Button variant="ghost" onClick={() => setDisputing(false)}>Cancel</Button></div>
            </div>
          )}
        </div>
      )}
    </Modal>
  );
}

function StatementModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const monthAgo = new Date(Date.now() - 30 * 86400_000).toISOString().slice(0, 10);
  const [from, setFrom] = useState(monthAgo);
  const [to, setTo] = useState(new Date().toISOString().slice(0, 10));
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const download = async (format: 'csv' | 'pdf') => {
    setBusy(format);
    setError(null);
    try {
      const href = await transactionApi.statementUrl(new Date(from).toISOString(), new Date(`${to}T23:59:59`).toISOString(), format);
      const a = document.createElement('a');
      a.href = href;
      a.download = `payverse-statement-${from}-to-${to}.${format}`;
      a.click();
      setTimeout(() => URL.revokeObjectURL(href), 10_000);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(null);
    }
  };
  return (
    <Modal title="Download statement" open={open} onClose={onClose}>
      <div className="form">
        <div className="form-row">
          <TextInput label="From" type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} />
          <TextInput label="To" type="date" value={to} min={from} onChange={(e) => setTo(e.target.value)} />
        </div>
        <p className="small muted">Up to one year per statement.</p>
        <ErrorBanner error={error} />
        <div className="row">
          <Button busy={busy === 'pdf'} onClick={() => void download('pdf')}>PDF</Button>
          <Button variant="secondary" busy={busy === 'csv'} onClick={() => void download('csv')}>CSV</Button>
        </div>
      </div>
    </Modal>
  );
}
