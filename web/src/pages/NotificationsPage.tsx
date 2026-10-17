import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { notificationApi } from '../api/endpoints';
import { Button, Card, Empty, ErrorBanner, PageHeader, Pager, Spinner } from '../components/ui';
import { relativeTime } from '../lib/format';

const ICON: Record<string, string> = { SMS: '💬', EMAIL: '✉️', PUSH: '🔔' };

export default function NotificationsPage() {
  const [page, setPage] = useState(0);
  const inbox = useQuery({ queryKey: ['inbox', page], queryFn: () => notificationApi.inbox(page) });
  const queryClient = useQueryClient();
  const read = useMutation({
    mutationFn: (id: string) => notificationApi.markRead(id),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['inbox'] }),
  });
  const unread = inbox.data?.items.filter((n) => !n.readAt) ?? [];
  const readAll = async () => {
    await Promise.all(unread.map((n) => notificationApi.markRead(n.id)));
    void queryClient.invalidateQueries({ queryKey: ['inbox'] });
  };
  return (
    <div className="stack">
      <PageHeader title="Notifications" subtitle={inbox.data ? `${inbox.data.unread} unread` : undefined}
                  action={unread.length > 0 && <Button variant="secondary" onClick={() => void readAll()}>Mark all read</Button>} />
      <Card>
        {inbox.isLoading ? <Spinner /> : inbox.error ? <ErrorBanner error={inbox.error} /> : !inbox.data?.items.length ? <Empty title="You're all caught up" /> : (
          <>
            <ul className="list">
              {inbox.data.items.map((n) => (
                <li key={n.id} className={`list-item notification ${n.readAt ? '' : 'unread clickable'}`} onClick={() => !n.readAt && read.mutate(n.id)}>
                  <span className="avatar" aria-label={n.channel}>{ICON[n.channel]}</span>
                  <div className="list-main">
                    <div className="list-title">{n.subject ?? n.body.split('.')[0]}</div>
                    <div className="list-sub" style={{ whiteSpace: 'normal' }}>{n.body}</div>
                  </div>
                  <span className="small muted">{relativeTime(n.createdAt)}</span>
                </li>
              ))}
            </ul>
            <Pager page={page} totalPages={Math.ceil((inbox.data.totalElements ?? 0) / 30)} onChange={setPage} />
          </>
        )}
      </Card>
    </div>
  );
}
