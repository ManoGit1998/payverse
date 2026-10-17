import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { adminApi } from '../../api/endpoints';
import { errorMessage } from '../../api/errors';
import type { Template } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { useToast } from '../../components/feedback';
import { Badge, Button, Card, Empty, ErrorBanner, Modal, PageHeader, Spinner, Tabs, TextInput } from '../../components/ui';
import { formatDateTime } from '../../lib/format';

type Tab = 'templates' | 'dlt';

export default function NotificationsAdminPage() {
  const [tab, setTab] = useState<Tab>('templates');
  return (
    <div className="stack">
      <PageHeader title="Templates & dead letters" subtitle="Message templates, and Kafka messages that failed in any service" />
      <Tabs<Tab> value={tab} onChange={setTab} options={[{ value: 'templates', label: 'Templates' }, { value: 'dlt', label: 'Dead letters' }]} />
      {tab === 'templates' ? <Templates /> : <DeadLetters />}
    </div>
  );
}

function Templates() {
  const { isAdmin } = useAuth();
  const templates = useQuery({ queryKey: ['templates'], queryFn: adminApi.templates });
  const [editing, setEditing] = useState<Template | null>(null);
  return (
    <Card>
      {templates.isLoading ? <Spinner /> : templates.error ? <ErrorBanner error={templates.error} /> : (
        <div className="table-wrap">
          <table>
            <thead><tr><th>Code</th><th>Channel</th><th>Message</th><th>Status</th><th /></tr></thead>
            <tbody>
              {templates.data?.map((t) => (
                <tr key={t.id}>
                  <td className="mono small">{t.code}</td>
                  <td><Badge value={t.channel} tone="info" /></td>
                  <td>{t.subject && <strong>{t.subject} — </strong>}<span className="small">{t.body}</span></td>
                  <td><Badge value={t.active ? 'ACTIVE' : 'DISABLED'} tone={t.active ? 'success' : 'neutral'} /></td>
                  <td>{isAdmin && <Button variant="ghost" onClick={() => setEditing(t)}>Edit</Button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {editing && <EditTemplate template={editing} onClose={() => setEditing(null)} />}
    </Card>
  );
}

function EditTemplate({ template, onClose }: { template: Template; onClose: () => void }) {
  const [subject, setSubject] = useState(template.subject ?? '');
  const [body, setBody] = useState(template.body);
  const [active, setActive] = useState(template.active);
  const toast = useToast();
  const queryClient = useQueryClient();
  const save = useMutation({
    mutationFn: () => adminApi.updateTemplate(template.code, template.channel, { subject: subject || null, body, active }),
    onSuccess: () => { toast('Template saved'); void queryClient.invalidateQueries({ queryKey: ['templates'] }); onClose(); },
  });
  return (
    <Modal title={`${template.code} · ${template.channel}`} open onClose={onClose}
           footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button busy={save.isPending} disabled={!body.trim()} onClick={() => save.mutate()}>Save</Button></>}>
      <div className="form">
        {template.channel !== 'SMS' && <TextInput label="Subject" value={subject} onChange={(e) => setSubject(e.target.value)} />}
        <div className="field">
          <label htmlFor="tpl-body">Body</label>
          <textarea id="tpl-body" className="input" rows={4} value={body} onChange={(e) => setBody(e.target.value)} />
          <p className="field-hint">Placeholders look like {'{{amount}}'}; unknown ones render empty.</p>
        </div>
        <label className="checkbox"><input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} /> Active</label>
        <ErrorBanner error={save.error} />
      </div>
    </Modal>
  );
}

function DeadLetters() {
  const { isAdmin } = useAuth();
  const [onlyOpen, setOnlyOpen] = useState(true);
  const letters = useQuery({ queryKey: ['dead-letters', onlyOpen], queryFn: () => adminApi.deadLetters(onlyOpen) });
  const toast = useToast();
  const queryClient = useQueryClient();
  const replay = useMutation({
    mutationFn: (id: string) => adminApi.replayDeadLetter(id),
    onSuccess: (d) => { toast(`Replayed to ${d.originalTopic}`); void queryClient.invalidateQueries({ queryKey: ['dead-letters'] }); },
    onError: (e) => toast(errorMessage(e), 'danger'),
  });
  return (
    <Card action={<label className="checkbox"><input type="checkbox" checked={onlyOpen} onChange={(e) => setOnlyOpen(e.target.checked)} /> Only not replayed</label>}
          title="Dead-lettered messages">
      {letters.isLoading ? <Spinner /> : letters.error ? <ErrorBanner error={letters.error} /> : !letters.data?.items.length ? <Empty title="No dead letters" /> : (
        <ul className="list">
          {letters.data.items.map((d) => (
            <li key={d.id} className="list-item" style={{ alignItems: 'flex-start' }}>
              <div className="list-main">
                <div className="list-title">{d.originalTopic} <span className="small muted">p{d.partition} · offset {d.offset}</span></div>
                <div className="list-sub" style={{ whiteSpace: 'normal' }}>{d.errorMessage}</div>
                <details><summary className="small">Payload</summary><pre className="pre">{d.payload}</pre></details>
                <div className="small muted">{formatDateTime(d.receivedAt)}{d.replayedAt ? ` · replayed ${formatDateTime(d.replayedAt)}` : ''}</div>
              </div>
              {!d.replayedAt && isAdmin && <Button variant="secondary" busy={replay.isPending && replay.variables === d.id} onClick={() => replay.mutate(d.id)}>Replay</Button>}
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}
