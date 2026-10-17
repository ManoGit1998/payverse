import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { adminApi } from '../../api/endpoints';
import { useAuth } from '../../auth/AuthContext';
import { useToast } from '../../components/feedback';
import { AmountInput, Badge, Button, Card, ErrorBanner, Modal, PageHeader, Spinner, TextInput } from '../../components/ui';
import { formatDate, formatMoney, humanize, isValidAmount } from '../../lib/format';

export default function RewardsAdminPage() {
  const { isAdmin } = useAuth();
  const rules = useQuery({ queryKey: ['admin-cashback-rules'], queryFn: adminApi.cashbackRules });
  const coupons = useQuery({ queryKey: ['admin-coupons'], queryFn: adminApi.coupons });
  const [creating, setCreating] = useState(false);
  const toast = useToast();
  const queryClient = useQueryClient();
  const toggle = useMutation({
    mutationFn: ({ code, active }: { code: string; active: boolean }) => adminApi.setCouponActive(code, active),
    onSuccess: (c) => { toast(`${c.code} ${c.active ? 'activated' : 'deactivated'}`); void queryClient.invalidateQueries({ queryKey: ['admin-coupons'] }); },
  });
  return (
    <div className="stack">
      <PageHeader title="Rules & coupons" subtitle="Cashback rules (first match with budget wins) and coupons" />
      <Card title="Cashback rules">
        {rules.isLoading ? <Spinner /> : rules.error ? <ErrorBanner error={rules.error} /> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Rule</th><th>Trigger</th><th>Reward</th><th>Conditions</th><th>Budget used</th><th>Priority</th><th>Status</th></tr></thead>
              <tbody>
                {rules.data?.map((r) => (
                  <tr key={r.id}>
                    <td><strong>{r.code}</strong><div className="small muted">{r.name}</div></td>
                    <td>{humanize(r.trigger)}</td>
                    <td>{r.rewardType === 'FLAT' ? formatMoney(r.flatAmount) : r.rewardType === 'PERCENT' ? `${r.percent}% (max ${formatMoney(r.maxReward)})` : `Scratch ${formatMoney(r.minReward)}–${formatMoney(r.maxReward)}`}</td>
                    <td className="small">{[r.minTxnAmount && `≥ ${formatMoney(r.minTxnAmount)}`, r.maxTxnAmount && `≤ ${formatMoney(r.maxTxnAmount)}`, r.firstTimeOnly && 'first time', r.dailyCapPerUser && `${r.dailyCapPerUser}/day`].filter(Boolean).join(' · ') || '—'}</td>
                    <td>{formatMoney(r.spent)}{r.budget !== null && ` of ${formatMoney(r.budget)}`}</td>
                    <td>{r.priority}</td>
                    <td><Badge value={r.active ? 'ACTIVE' : 'DISABLED'} tone={r.active ? 'success' : 'neutral'} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p className="small muted" style={{ marginTop: 10 }}>Create or edit rules with the API (see http/07-rewards.http).</p>
      </Card>
      <Card title="Coupons" action={isAdmin && <Button onClick={() => setCreating(true)}>New coupon</Button>}>
        {coupons.isLoading ? <Spinner /> : coupons.error ? <ErrorBanner error={coupons.error} /> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Code</th><th>Cashback</th><th>Used</th><th>Valid until</th><th>Status</th><th /></tr></thead>
              <tbody>
                {coupons.data?.map((c) => (
                  <tr key={c.id}>
                    <td><strong>{c.code}</strong><div className="small muted">{c.description}</div></td>
                    <td>{formatMoney(c.cashback)}</td>
                    <td>{c.usedCount} / {c.usageLimit}</td>
                    <td>{formatDate(c.validUntil)}</td>
                    <td><Badge value={c.active ? 'ACTIVE' : 'DISABLED'} tone={c.active ? 'success' : 'neutral'} /></td>
                    <td>{isAdmin && <Button variant="ghost" onClick={() => toggle.mutate({ code: c.code, active: !c.active })}>{c.active ? 'Deactivate' : 'Activate'}</Button>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      <CreateCoupon open={creating} onClose={() => setCreating(false)} />
    </div>
  );
}

function CreateCoupon({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [code, setCode] = useState('');
  const [description, setDescription] = useState('');
  const [cashback, setCashback] = useState('');
  const [until, setUntil] = useState(new Date(Date.now() + 30 * 86400_000).toISOString().slice(0, 10));
  const [limit, setLimit] = useState('1000');
  const toast = useToast();
  const queryClient = useQueryClient();
  const create = useMutation({
    mutationFn: () => adminApi.createCoupon({ code, description, cashback: Number(cashback), validUntil: new Date(`${until}T23:59:59`).toISOString(), usageLimit: Number(limit) }),
    onSuccess: (c) => { toast(`Coupon ${c.code} created`); void queryClient.invalidateQueries({ queryKey: ['admin-coupons'] }); onClose(); },
  });
  const valid = /^[A-Z0-9]{4,32}$/.test(code) && description.trim().length > 2 && isValidAmount(cashback, 0.01) && Number(limit) > 0;
  return (
    <Modal title="New coupon" open={open} onClose={onClose}
           footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button busy={create.isPending} disabled={!valid} onClick={() => create.mutate()}>Create</Button></>}>
      <div className="form">
        <TextInput label="Code" value={code} maxLength={32} onChange={(e) => setCode(e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, ''))} hint="4–32 letters and digits" />
        <TextInput label="Description" value={description} maxLength={200} onChange={(e) => setDescription(e.target.value)} />
        <AmountInput label="Cashback" value={cashback} onChange={setCashback} />
        <div className="form-row">
          <TextInput label="Valid until" type="date" value={until} onChange={(e) => setUntil(e.target.value)} />
          <TextInput label="Usage limit" inputMode="numeric" value={limit} onChange={(e) => setLimit(e.target.value.replace(/\D/g, ''))} />
        </div>
        <ErrorBanner error={create.error} />
      </div>
    </Modal>
  );
}
