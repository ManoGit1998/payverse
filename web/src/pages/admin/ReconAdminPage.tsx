import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { adminApi } from '../../api/endpoints';
import type { ReconJob, ReconMismatch } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { useToast } from '../../components/feedback';
import { Badge, Button, Card, Empty, ErrorBanner, Modal, PageHeader, Select, Spinner, TextInput } from '../../components/ui';
import { formatDate, formatDateTime, formatMoney, humanize } from '../../lib/format';

const JOBS: { value: ReconJob; label: string }[] = [
  { value: 'PG_VS_LEDGER', label: 'PG charges vs wallet ledger' },
  { value: 'TRANSFERS_VS_UPI', label: 'Transfers vs UPI switch' },
  { value: 'WALLET_INTEGRITY', label: 'Ledger integrity' },
];

export default function ReconAdminPage() {
  const { isAdmin } = useAuth();
  const [job, setJob] = useState<ReconJob>('PG_VS_LEDGER');
  const [date, setDate] = useState(new Date().toISOString().slice(0, 10));
  const [openRun, setOpenRun] = useState<string | null>(null);
  const runs = useQuery({ queryKey: ['recon-runs'], queryFn: () => adminApi.reconRuns(), refetchInterval: (q) => (q.state.data?.items.some((r) => r.status === 'RUNNING') ? 2000 : false) });
  const open = useQuery({ queryKey: ['recon-open'], queryFn: adminApi.openMismatches });
  const toast = useToast();
  const queryClient = useQueryClient();
  const run = useMutation({
    mutationFn: () => adminApi.runRecon(job, date),
    onSuccess: () => { toast('Run started'); void queryClient.invalidateQueries({ queryKey: ['recon-runs'] }); },
  });
  return (
    <div className="stack">
      <PageHeader title="Reconciliation" subtitle="Spring Batch jobs; nightly at 02:30 IST for the previous day" />
      {isAdmin && (
        <Card title="Run a job now">
          <form className="form-row" style={{ alignItems: 'flex-end' }} onSubmit={(e) => { e.preventDefault(); run.mutate(); }}>
            <Select label="Job" value={job} onChange={(e) => setJob(e.target.value as ReconJob)}>{JOBS.map((j) => <option key={j.value} value={j.value}>{j.label}</option>)}</Select>
            <TextInput label="Business day (IST)" type="date" value={date} onChange={(e) => setDate(e.target.value)} />
            <Button type="submit" busy={run.isPending}>Run</Button>
          </form>
          <ErrorBanner error={run.error} />
        </Card>
      )}
      <Card title="Open mismatches" action={<span className="small muted">{open.data?.items.length ?? 0} open</span>}>
        {open.isLoading ? <Spinner /> : !open.data?.items.length ? <Empty title="Everything reconciles" /> : <MismatchTable items={open.data.items} />}
      </Card>
      <Card title="Runs">
        {runs.isLoading ? <Spinner /> : runs.error ? <ErrorBanner error={runs.error} /> : !runs.data?.items.length ? <Empty title="No runs yet" /> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Started</th><th>Job</th><th>Day</th><th>Left / right / matched</th><th>Mismatches</th><th>Status</th><th /></tr></thead>
              <tbody>
                {runs.data.items.map((r) => (
                  <tr key={r.id}>
                    <td className="small">{formatDateTime(r.startedAt)}<div className="muted">{r.triggeredBy === 'scheduler' ? 'nightly' : 'manual'}</div></td>
                    <td>{humanize(r.job)}</td>
                    <td>{formatDate(r.businessDate)}</td>
                    <td>{r.leftCount} / {r.rightCount} / {r.matchedCount}</td>
                    <td>{r.mismatchCount > 0 ? <strong style={{ color: 'var(--danger)' }}>{r.mismatchCount}</strong> : 0}</td>
                    <td><Badge value={r.status} />{r.error && <div className="small muted">{r.error}</div>}</td>
                    <td><Button variant="ghost" onClick={() => setOpenRun(r.id)} disabled={r.status === 'RUNNING'}>Details</Button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      {openRun && <RunModal id={openRun} onClose={() => setOpenRun(null)} />}
    </div>
  );
}

function MismatchTable({ items }: { items: ReconMismatch[] }) {
  const { isAdmin } = useAuth();
  const [resolving, setResolving] = useState<ReconMismatch | null>(null);
  return (
    <>
      <div className="table-wrap">
        <table>
          <thead><tr><th>Type</th><th>Reference</th><th>Expected</th><th>Actual</th><th>Status</th><th /></tr></thead>
          <tbody>
            {items.map((m) => (
              <tr key={m.id}>
                <td>{humanize(m.type)}<div className="small muted">{humanize(m.job)}</div></td>
                <td className="mono small">{m.reference}</td>
                <td>{m.expectedAmount !== null ? formatMoney(m.expectedAmount) : '—'}</td>
                <td>{m.actualAmount !== null ? formatMoney(m.actualAmount) : '—'}</td>
                <td><Badge value={m.status} />{m.note && <div className="small muted">{m.note}</div>}</td>
                <td>{m.status === 'OPEN' && isAdmin && <Button variant="ghost" onClick={() => setResolving(m)}>Resolve</Button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {resolving && <ResolveMismatch mismatch={resolving} onClose={() => setResolving(null)} />}
    </>
  );
}

function ResolveMismatch({ mismatch, onClose }: { mismatch: ReconMismatch; onClose: () => void }) {
  const [note, setNote] = useState('');
  const queryClient = useQueryClient();
  const resolve = useMutation({
    mutationFn: () => adminApi.resolveMismatch(mismatch.id, note),
    onSuccess: () => { void queryClient.invalidateQueries({ queryKey: ['recon-open'] }); void queryClient.invalidateQueries({ queryKey: ['recon-mismatches'] }); onClose(); },
  });
  return (
    <Modal title={`Resolve ${humanize(mismatch.type).toLowerCase()}`} open onClose={onClose}
           footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button busy={resolve.isPending} disabled={note.trim().length < 3} onClick={() => resolve.mutate()}>Resolve</Button></>}>
      <div className="form">
        <p className="mono small">{mismatch.reference}</p>
        <TextInput label="What happened / what was done" value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} />
        <ErrorBanner error={resolve.error} />
      </div>
    </Modal>
  );
}

function RunModal({ id, onClose }: { id: string; onClose: () => void }) {
  const mismatches = useQuery({ queryKey: ['recon-mismatches', id], queryFn: () => adminApi.reconMismatches(id) });
  return (
    <Modal title="Run mismatches" open onClose={onClose}>
      {mismatches.isLoading ? <Spinner /> : mismatches.error ? <ErrorBanner error={mismatches.error} /> : !mismatches.data?.items.length ? <Empty title="No mismatches in this run" /> : <MismatchTable items={mismatches.data.items} />}
    </Modal>
  );
}
