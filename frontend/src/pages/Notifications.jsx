import { get, post } from '../api';
import { fmtDate, label } from '../format';
import { Card, Empty, Loader, useLoad } from '../components/ui';

export default function Notifications() {
  const s = useLoad(() => get('/notifications?size=100'));
  const read = (id) => post(`/notifications/${id}/read`).then(s.reload);
  return (
    <Card title="Notifications" actions={<button onClick={() => post('/notifications/read-all').then(s.reload)}>Mark all read</button>}>
      <Loader state={s}>{(d) => d.items.length === 0 ? <Empty>No notifications yet.</Empty> : (
        <ul className="feed">
          {d.items.map((n) => (
            <li key={n.id} className={n.readAt ? '' : 'unread'} onClick={() => !n.readAt && read(n.id)}>
              <div className="row between"><b>{n.title}</b><small className="muted">{fmtDate(n.createdAt)}</small></div>
              <p>{n.message}</p>
              <small className="muted">{label(n.type)} · email {label(n.emailStatus)}</small>
            </li>
          ))}
        </ul>
      )}</Loader>
    </Card>
  );
}
