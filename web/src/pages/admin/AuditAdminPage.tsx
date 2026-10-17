import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { adminApi } from '../../api/endpoints';
import { Button, Card, Empty, ErrorBanner, PageHeader, Spinner, TextInput } from '../../components/ui';
import { formatDateTime, humanize } from '../../lib/format';

export default function AuditAdminPage() {
  const [form, setForm] = useState({ actor: '', action: '', entityType: '', entityId: '' });
  const [query, setQuery] = useState(form);
  const [page, setPage] = useState(0);
  const logs = useQuery({ queryKey: ['audit', query, page], queryFn: () => adminApi.auditLogs({ ...clean(query), page }) });
  return (
    <div className="stack">
      <PageHeader title="Audit log" subtitle="Append-only record of every back-office action" />
      <Card>
        <form className="form-row" style={{ alignItems: 'flex-end' }} onSubmit={(e) => { e.preventDefault(); setPage(0); setQuery(form); }}>
          <TextInput label="Actor" value={form.actor} onChange={(e) => setForm({ ...form, actor: e.target.value })} />
          <TextInput label="Action" value={form.action} placeholder="REFUND_APPROVED" onChange={(e) => setForm({ ...form, action: e.target.value.toUpperCase() })} />
          <TextInput label="Entity type" value={form.entityType} placeholder="Dispute" onChange={(e) => setForm({ ...form, entityType: e.target.value })} />
          <TextInput label="Entity ID" value={form.entityId} onChange={(e) => setForm({ ...form, entityId: e.target.value })} />
          <Button type="submit">Filter</Button>
        </form>
      </Card>
      <Card>
        {logs.isLoading ? <Spinner /> : logs.error ? <ErrorBanner error={logs.error} /> : !logs.data?.length ? <Empty title="No entries" /> : (
          <>
            <div className="table-wrap">
              <table>
                <thead><tr><th>When</th><th>Actor</th><th>Action</th><th>Entity</th><th>Details</th></tr></thead>
                <tbody>
                  {logs.data.map((a) => (
                    <tr key={a.id}>
                      <td className="small">{formatDateTime(a.createdAt)}</td>
                      <td className="mono small">{a.actor}</td>
                      <td>{humanize(a.action)}</td>
                      <td>{a.entityType}<div className="mono small muted">{a.entityId}</div></td>
                      <td><code className="small">{a.details}</code></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="pager">
              <Button variant="ghost" disabled={page === 0} onClick={() => setPage(page - 1)}>← Newer</Button>
              <Button variant="ghost" disabled={logs.data.length < 100} onClick={() => setPage(page + 1)}>Older →</Button>
            </div>
          </>
        )}
      </Card>
    </div>
  );
}

function clean(q: Record<string, string>) {
  return Object.fromEntries(Object.entries(q).filter(([, v]) => v.trim() !== ''));
}
