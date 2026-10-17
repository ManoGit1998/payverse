import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { adminApi } from '../../api/endpoints';
import { errorMessage } from '../../api/errors';
import type { Dispute } from '../../api/types';
import { useToast } from '../../components/feedback';
import { Badge, Button, Card, Empty, ErrorBanner, Modal, PageHeader, Spinner, Tabs, TextInput } from '../../components/ui';
import { formatDateTime } from '../../lib/format';

type Status = 'OPEN' | 'IN_REVIEW' | 'RESOLVED';

export default function DisputesAdminPage() {
  const [status, setStatus] = useState<Status>('OPEN');
  const [resolving, setResolving] = useState<Dispute | null>(null);
  const disputes = useQuery({ queryKey: ['admin-disputes', status], queryFn: () => adminApi.disputes(status) });
  const toast = useToast();
  const queryClient = useQueryClient();
  const assign = useMutation({
    mutationFn: (id: string) => adminApi.assignDispute(id),
    onSuccess: () => { toast('Assigned to you'); void queryClient.invalidateQueries({ queryKey: ['admin-disputes'] }); },
    onError: (e) => toast(errorMessage(e), 'danger'),
  });
  return (
    <div className="stack">
      <PageHeader title="Disputes" subtitle="Resolving with a refund opens a refund request that another admin approves" />
      <Tabs<Status> value={status} onChange={setStatus} options={[{ value: 'OPEN', label: 'Open' }, { value: 'IN_REVIEW', label: 'In review' }, { value: 'RESOLVED', label: 'Resolved' }]} />
      <Card>
        {disputes.isLoading ? <Spinner /> : disputes.error ? <ErrorBanner error={disputes.error} /> : !disputes.data?.items.length ? <Empty title="Queue is empty" /> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Opened</th><th>Reason</th><th>Transaction</th><th>Assignee</th><th>Status</th><th /></tr></thead>
              <tbody>
                {disputes.data.items.map((d) => (
                  <tr key={d.id}>
                    <td className="small">{formatDateTime(d.createdAt)}</td>
                    <td>{d.reason}{d.resolutionNote && <div className="small muted">→ {d.resolutionNote}</div>}</td>
                    <td className="mono small">{d.transactionId}</td>
                    <td>{d.assignee ?? '—'}</td>
                    <td><Badge value={d.status} />{d.outcome && <> <Badge value={d.outcome} /></>}</td>
                    <td className="row">
                      {d.status === 'OPEN' && <Button variant="ghost" onClick={() => assign.mutate(d.id)}>Take</Button>}
                      {d.status !== 'RESOLVED' && <Button variant="secondary" onClick={() => setResolving(d)}>Resolve</Button>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      {resolving && <ResolveModal dispute={resolving} onClose={() => setResolving(null)} />}
    </div>
  );
}

function ResolveModal({ dispute, onClose }: { dispute: Dispute; onClose: () => void }) {
  const [note, setNote] = useState('');
  const toast = useToast();
  const queryClient = useQueryClient();
  const resolve = useMutation({
    mutationFn: (outcome: 'REFUND' | 'REJECTED') => adminApi.resolveDispute(dispute.id, outcome, note),
    onSuccess: (d) => {
      toast(d.outcome === 'REFUND' ? 'Refund requested — waiting for an admin to approve' : 'Dispute rejected');
      void queryClient.invalidateQueries({ queryKey: ['admin-disputes'] });
      void queryClient.invalidateQueries({ queryKey: ['admin-refunds'] });
      onClose();
    },
  });
  return (
    <Modal title="Resolve dispute" open onClose={onClose}>
      <div className="form">
        <p><strong>{dispute.reason}</strong></p>
        <TextInput label="Resolution note" value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} />
        <ErrorBanner error={resolve.error} />
        <div className="row">
          <Button busy={resolve.isPending && resolve.variables === 'REFUND'} onClick={() => resolve.mutate('REFUND')}>Refund (full)</Button>
          <Button variant="secondary" busy={resolve.isPending && resolve.variables === 'REJECTED'} onClick={() => resolve.mutate('REJECTED')}>Reject</Button>
        </div>
      </div>
    </Modal>
  );
}
