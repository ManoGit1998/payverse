import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { adminApi } from '../../api/endpoints';
import type { RiskFlag, RiskRule } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { useToast } from '../../components/feedback';
import { Badge, Button, Card, Empty, ErrorBanner, Modal, PageHeader, Spinner, Tabs, TextInput } from '../../components/ui';
import { formatDateTime, humanize } from '../../lib/format';

type Tab = 'OPEN' | 'CONFIRMED_FRAUD' | 'FALSE_POSITIVE' | 'rules';

export default function RiskAdminPage() {
  const [tab, setTab] = useState<Tab>('OPEN');
  return (
    <div className="stack">
      <PageHeader title="Risk" subtitle="Score ≥ 50 is flagged for review, ≥ 80 is blocked" />
      <Tabs<Tab> value={tab} onChange={setTab} options={[{ value: 'OPEN', label: 'Open flags' }, { value: 'CONFIRMED_FRAUD', label: 'Confirmed fraud' }, { value: 'FALSE_POSITIVE', label: 'False positives' }, { value: 'rules', label: 'Rules' }]} />
      {tab === 'rules' ? <Rules /> : <Flags status={tab} />}
    </div>
  );
}

function Flags({ status }: { status: string }) {
  const flags = useQuery({ queryKey: ['risk-flags', status], queryFn: () => adminApi.riskFlags(status) });
  const [resolving, setResolving] = useState<RiskFlag | null>(null);
  return (
    <Card>
      {flags.isLoading ? <Spinner /> : flags.error ? <ErrorBanner error={flags.error} /> : !flags.data?.items.length ? <Empty title="No flags" /> : (
        <div className="table-wrap">
          <table>
            <thead><tr><th>When</th><th>Decision</th><th>Score</th><th>Rules triggered</th><th>Transaction / user</th><th /></tr></thead>
            <tbody>
              {flags.data.items.map((f) => (
                <tr key={f.id}>
                  <td className="small">{formatDateTime(f.createdAt)}</td>
                  <td><Badge value={f.decision} /></td>
                  <td><strong>{f.score}</strong></td>
                  <td className="small">{f.reasons.split(',').map(humanize).join(', ')}</td>
                  <td className="mono small">{f.transactionId}<div className="muted">{f.userId}</div></td>
                  <td>{f.status === 'OPEN' ? <Button variant="secondary" onClick={() => setResolving(f)}>Resolve</Button> : <span className="small muted">{f.resolutionNote}{f.walletFrozen ? ' · wallet frozen' : ''}</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {resolving && <ResolveFlag flag={resolving} onClose={() => setResolving(null)} />}
    </Card>
  );
}

function ResolveFlag({ flag, onClose }: { flag: RiskFlag; onClose: () => void }) {
  const { isAdmin } = useAuth();
  const [note, setNote] = useState('');
  const [freeze, setFreeze] = useState(false);
  const toast = useToast();
  const queryClient = useQueryClient();
  const resolve = useMutation({
    mutationFn: (resolution: 'CONFIRMED_FRAUD' | 'FALSE_POSITIVE') => adminApi.resolveFlag(flag.id, resolution, note, resolution === 'CONFIRMED_FRAUD' && freeze),
    onSuccess: () => { toast('Flag resolved'); void queryClient.invalidateQueries({ queryKey: ['risk-flags'] }); onClose(); },
  });
  return (
    <Modal title={`Resolve ${flag.decision.toLowerCase()} flag (score ${flag.score})`} open onClose={onClose}>
      <div className="form">
        <TextInput label="Note" value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} placeholder="Customer confirmed on call…" />
        {isAdmin && <label className="checkbox"><input type="checkbox" checked={freeze} onChange={(e) => setFreeze(e.target.checked)} /> Freeze the wallet when confirming fraud</label>}
        <ErrorBanner error={resolve.error} />
        <div className="row">
          <Button variant="danger" busy={resolve.isPending && resolve.variables === 'CONFIRMED_FRAUD'} onClick={() => resolve.mutate('CONFIRMED_FRAUD')}>Confirmed fraud</Button>
          <Button variant="secondary" busy={resolve.isPending && resolve.variables === 'FALSE_POSITIVE'} onClick={() => resolve.mutate('FALSE_POSITIVE')}>False positive</Button>
        </div>
      </div>
    </Modal>
  );
}

function Rules() {
  const { isAdmin } = useAuth();
  const rules = useQuery({ queryKey: ['risk-rules'], queryFn: adminApi.riskRules });
  const [editing, setEditing] = useState<RiskRule | null>(null);
  return (
    <Card>
      {rules.isLoading ? <Spinner /> : rules.error ? <ErrorBanner error={rules.error} /> : (
        <div className="table-wrap">
          <table>
            <thead><tr><th>Rule</th><th>Weight</th><th>Amount</th><th>Count</th><th>Window</th><th>Multiplier</th><th>Enabled</th><th /></tr></thead>
            <tbody>
              {rules.data?.map((r) => (
                <tr key={r.code}>
                  <td><strong>{r.code}</strong><div className="small muted">{r.description}</div></td>
                  <td>{r.weight}</td><td>{r.thresholdAmount ?? '—'}</td><td>{r.thresholdCount ?? '—'}</td>
                  <td>{r.windowSeconds ? `${Math.round(r.windowSeconds / 60)} min` : '—'}</td><td>{r.multiplier ?? '—'}</td>
                  <td><Badge value={r.enabled ? 'ACTIVE' : 'DISABLED'} tone={r.enabled ? 'success' : 'neutral'} /></td>
                  <td>{isAdmin && <Button variant="ghost" onClick={() => setEditing(r)}>Tune</Button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {editing && <TuneRule rule={editing} onClose={() => setEditing(null)} />}
    </Card>
  );
}

function TuneRule({ rule, onClose }: { rule: RiskRule; onClose: () => void }) {
  const [v, setV] = useState({ weight: String(rule.weight), enabled: rule.enabled, thresholdAmount: String(rule.thresholdAmount ?? ''), thresholdCount: String(rule.thresholdCount ?? ''),
    windowSeconds: String(rule.windowSeconds ?? ''), multiplier: String(rule.multiplier ?? '') });
  const num = (s: string) => (s.trim() === '' ? null : Number(s));
  const toast = useToast();
  const queryClient = useQueryClient();
  const save = useMutation({
    mutationFn: () => adminApi.updateRiskRule(rule.code, { weight: Number(v.weight), enabled: v.enabled, thresholdAmount: num(v.thresholdAmount),
      thresholdCount: num(v.thresholdCount), windowSeconds: num(v.windowSeconds), multiplier: num(v.multiplier) }),
    onSuccess: () => { toast(`${rule.code} updated`); void queryClient.invalidateQueries({ queryKey: ['risk-rules'] }); onClose(); },
  });
  return (
    <Modal title={`Tune ${rule.code}`} open onClose={onClose}
           footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button busy={save.isPending} onClick={() => save.mutate()}>Save</Button></>}>
      <div className="form">
        <p className="small muted">{rule.description}</p>
        <div className="form-row">
          <TextInput label="Weight (0–100)" inputMode="numeric" value={v.weight} onChange={(e) => setV({ ...v, weight: e.target.value })} />
          <TextInput label="Threshold amount" inputMode="decimal" value={v.thresholdAmount} onChange={(e) => setV({ ...v, thresholdAmount: e.target.value })} />
          <TextInput label="Threshold count" inputMode="numeric" value={v.thresholdCount} onChange={(e) => setV({ ...v, thresholdCount: e.target.value })} />
          <TextInput label="Window (seconds)" inputMode="numeric" value={v.windowSeconds} onChange={(e) => setV({ ...v, windowSeconds: e.target.value })} />
          <TextInput label="Multiplier" inputMode="decimal" value={v.multiplier} onChange={(e) => setV({ ...v, multiplier: e.target.value })} />
        </div>
        <label className="checkbox"><input type="checkbox" checked={v.enabled} onChange={(e) => setV({ ...v, enabled: e.target.checked })} /> Enabled</label>
        <ErrorBanner error={save.error} />
      </div>
    </Modal>
  );
}
