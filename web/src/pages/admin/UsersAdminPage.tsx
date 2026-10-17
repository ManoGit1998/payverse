import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { adminApi } from '../../api/endpoints';
import type { AdminUser } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { useToast } from '../../components/feedback';
import { Amount, Badge, Button, Card, Empty, ErrorBanner, KeyValue, Modal, PageHeader, Select, Spinner, TextInput } from '../../components/ui';

export default function UsersAdminPage() {
  const { isAdmin } = useAuth();
  const [mobile, setMobile] = useState('');
  const [name, setName] = useState('');
  const [tier, setTier] = useState('');
  const [query, setQuery] = useState<{ mobile?: string; name?: string; kycTier?: string } | null>(null);
  const [selected, setSelected] = useState<AdminUser | null>(null);
  const results = useQuery({ queryKey: ['admin-users', query], queryFn: () => adminApi.searchUsers(query!), enabled: query !== null });
  const toast = useToast();
  const rotate = useMutation({ mutationFn: adminApi.rotateSigningKey, onSuccess: (r) => toast(`Signing key rotated (kid ${r.kid.slice(0, 8)}…)`) });

  return (
    <div className="stack">
      <PageHeader title="Users" subtitle="Find a customer, check their wallet, freeze it if needed"
                  action={isAdmin && <Button variant="secondary" busy={rotate.isPending} onClick={() => rotate.mutate()}>Rotate JWT signing key</Button>} />
      <Card>
        <form className="form-row" style={{ alignItems: 'flex-end' }} onSubmit={(e) => { e.preventDefault(); setQuery(mobile ? { mobile } : { name: name || undefined, kycTier: tier || undefined }); }}>
          <TextInput label="Mobile (exact)" inputMode="numeric" maxLength={10} value={mobile} onChange={(e) => setMobile(e.target.value.replace(/\D/g, ''))} />
          <TextInput label="Name contains" value={name} disabled={Boolean(mobile)} onChange={(e) => setName(e.target.value)} />
          <Select label="KYC tier" value={tier} disabled={Boolean(mobile)} onChange={(e) => setTier(e.target.value)}>
            <option value="">Any</option><option value="NONE">None</option><option value="MIN_KYC">Min KYC</option><option value="FULL_KYC">Full KYC</option>
          </Select>
          <Button type="submit" disabled={!mobile && !name && !tier}>Search</Button>
        </form>
      </Card>
      <Card>
        {query === null ? <p className="muted">Search by mobile, or by name and/or KYC tier.</p> : results.isLoading ? <Spinner /> : results.error ? <ErrorBanner error={results.error} /> : !results.data?.length ? <Empty title="No users found" /> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Name</th><th>Mobile</th><th>KYC</th><th>Wallet</th><th>Balance</th><th /></tr></thead>
              <tbody>
                {results.data.map((u) => (
                  <tr key={u.userId}>
                    <td>{u.fullName ?? '—'}<div className="small muted mono">{u.userId}</div></td>
                    <td className="mono">{u.mobileMasked ?? '—'}</td>
                    <td>{u.kycTier && <Badge value={u.kycTier} />}</td>
                    <td>{u.wallet ? <Badge value={u.wallet.status} /> : '—'}</td>
                    <td>{u.wallet ? <Amount value={u.wallet.balance} /> : '—'}</td>
                    <td><Button variant="ghost" onClick={() => setSelected(u)}>Open</Button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      {selected && <UserModal userId={selected.userId} onClose={() => setSelected(null)} />}
    </div>
  );
}

function UserModal({ userId, onClose }: { userId: string; onClose: () => void }) {
  const { isAdmin } = useAuth();
  const user = useQuery({ queryKey: ['admin-user', userId], queryFn: () => adminApi.user(userId) });
  const [reason, setReason] = useState('');
  const toast = useToast();
  const queryClient = useQueryClient();
  const done = (msg: string) => { toast(msg); void queryClient.invalidateQueries({ queryKey: ['admin-user', userId] }); void queryClient.invalidateQueries({ queryKey: ['admin-users'] }); };
  const freeze = useMutation({ mutationFn: () => adminApi.freeze(userId, reason), onSuccess: () => { setReason(''); done('Wallet frozen'); } });
  const unfreeze = useMutation({ mutationFn: () => adminApi.unfreeze(userId), onSuccess: () => done('Wallet unfrozen') });
  const u = user.data;
  return (
    <Modal title="Customer" open onClose={onClose}>
      {user.isLoading ? <Spinner /> : user.error ? <ErrorBanner error={user.error} /> : u && (
        <div className="stack">
          <KeyValue items={[['Name', u.fullName], ['Mobile', u.mobileMasked], ['KYC', u.kycTier && <Badge key="k" value={u.kycTier} />],
            ['Wallet', u.wallet && <Badge key="w" value={u.wallet.status} />], ['Balance', u.wallet && <Amount key="b" value={u.wallet.balance} />],
            ['Freeze reason', u.wallet?.statusReason], ['User ID', <code key="id" className="small">{u.userId}</code>]]} />
          {isAdmin && u.wallet && (u.wallet.status === 'ACTIVE' ? (
            <div className="form">
              <TextInput label="Reason for freezing" value={reason} maxLength={200} onChange={(e) => setReason(e.target.value)} />
              <ErrorBanner error={freeze.error} />
              <Button variant="danger" busy={freeze.isPending} disabled={reason.trim().length < 3} onClick={() => freeze.mutate()}>Freeze wallet</Button>
            </div>
          ) : (
            <><ErrorBanner error={unfreeze.error} /><Button busy={unfreeze.isPending} onClick={() => unfreeze.mutate()}>Unfreeze wallet</Button></>
          ))}
          {!isAdmin && <p className="small muted">Only admins can freeze or unfreeze wallets.</p>}
        </div>
      )}
    </Modal>
  );
}
