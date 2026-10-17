import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { adminApi } from '../../api/endpoints';
import { errorMessage } from '../../api/errors';
import type { RefundStatus } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { useToast } from '../../components/feedback';
import { Amount, Badge, Banner, Button, Card, Empty, ErrorBanner, Modal, PageHeader, Spinner, Tabs, TextInput } from '../../components/ui';
import { formatDateTime, humanize } from '../../lib/format';

type Filter = RefundStatus | 'ALL';

export default function RefundsAdminPage() {
  const { isAdmin, me } = useAuth();
  const [filter, setFilter] = useState<Filter>('REQUESTED');
  const [creating, setCreating] = useState(false);
  const refunds = useQuery({ queryKey: ['admin-refunds', filter], queryFn: () => adminApi.refunds(filter === 'ALL' ? undefined : filter) });
  const toast = useToast();
  const queryClient = useQueryClient();
  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['admin-refunds'] });
  const decide = useMutation({
    mutationFn: ({ id, approve }: { id: string; approve: boolean }) => (approve ? adminApi.approveRefund(id) : adminApi.rejectRefund(id, 'Not eligible')),
    onSuccess: (r) => { toast(`Refund ${humanize(r.status).toLowerCase()}${r.failureCode ? ` (${humanize(r.failureCode)})` : ''}`, r.status === 'FAILED' ? 'danger' : 'success'); refresh(); },
    onError: (e) => toast(errorMessage(e), 'danger'),
  });
  return (
    <div className="stack">
      <PageHeader title="Refunds" subtitle="Maker-checker: anyone on the team can request; a different admin approves"
                  action={<Button onClick={() => setCreating(true)}>Request refund</Button>} />
      <Banner tone="info">Approving reverses the transfer: the payee's credit is taken back first, then the payer is credited. If the payee has already spent it, the refund fails and nothing moves.</Banner>
      <Tabs<Filter> value={filter} onChange={setFilter} options={(['REQUESTED', 'PROCESSING', 'COMPLETED', 'FAILED', 'REJECTED', 'ALL'] as Filter[]).map((s) => ({ value: s, label: humanize(s) }))} />
      <Card>
        {refunds.isLoading ? <Spinner /> : refunds.error ? <ErrorBanner error={refunds.error} /> : !refunds.data?.items.length ? <Empty title="Nothing here" /> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Requested</th><th>Transaction</th><th>Amount</th><th>Reason</th><th>Maker → checker</th><th>Status</th><th /></tr></thead>
              <tbody>
                {refunds.data.items.map((r) => {
                  const own = r.requestedBy === me?.userId;
                  return (
                    <tr key={r.id}>
                      <td className="small">{formatDateTime(r.createdAt)}</td>
                      <td className="mono small">{r.transactionId}</td>
                      <td><Amount value={r.amount} /></td>
                      <td>{r.reason}</td>
                      <td className="small">{r.requestedBy.slice(0, 8)} → {r.decidedBy ? r.decidedBy.slice(0, 8) : '—'}</td>
                      <td><Badge value={r.status} />{r.failureCode && <div className="small muted">{humanize(r.failureCode)}</div>}</td>
                      <td className="row">
                        {r.status === 'REQUESTED' && isAdmin && !own && (
                          <>
                            <Button busy={decide.isPending && decide.variables?.id === r.id && decide.variables.approve} onClick={() => decide.mutate({ id: r.id, approve: true })}>Approve</Button>
                            <Button variant="ghost" onClick={() => decide.mutate({ id: r.id, approve: false })}>Reject</Button>
                          </>
                        )}
                        {r.status === 'REQUESTED' && own && <span className="small muted">your request</span>}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      <CreateRefundModal open={creating} onClose={() => setCreating(false)} onCreated={() => { setCreating(false); setFilter('REQUESTED'); refresh(); }} />
    </div>
  );
}

function CreateRefundModal({ open, onClose, onCreated }: { open: boolean; onClose: () => void; onCreated: () => void }) {
  const [transactionId, setTransactionId] = useState('');
  const [reason, setReason] = useState('');
  const create = useMutation({ mutationFn: () => adminApi.requestRefund(transactionId.trim(), reason), onSuccess: onCreated });
  const valid = /^[0-9a-f-]{36}$/i.test(transactionId.trim()) && reason.trim().length >= 3;
  return (
    <Modal title="Request a refund" open={open} onClose={onClose}
           footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button busy={create.isPending} disabled={!valid} onClick={() => create.mutate()}>Request</Button></>}>
      <div className="form">
        <TextInput label="Transaction ID" value={transactionId} onChange={(e) => setTransactionId(e.target.value)} placeholder="UUID of a SUCCESS transfer" />
        <TextInput label="Reason" value={reason} maxLength={200} onChange={(e) => setReason(e.target.value)} />
        <p className="small muted">Full refunds only.</p>
        <ErrorBanner error={create.error} />
      </div>
    </Modal>
  );
}
