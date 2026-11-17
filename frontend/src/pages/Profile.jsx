import { useState } from 'react';
import { patch, post } from '../api';
import { useAuth } from '../auth';
import { Card, ErrorBox, Field, useAction } from '../components/ui';
import { label } from '../format';

export default function Profile() {
  const { user, updateUser } = useAuth();
  const [p, setP] = useState({ phone: user.phone || '', department: user.department || '' });
  const [pw, setPw] = useState({ currentPassword: '', newPassword: '' });
  const a = useAction();
  const b = useAction();
  const [msg, setMsg] = useState('');
  return (
    <div className="grid two">
      <Card title="Profile">
        <dl className="kv">
          <dt>Name</dt><dd>{user.fullName}</dd>
          <dt>Email</dt><dd>{user.email}</dd>
          <dt>ID</dt><dd>{user.institutionId || '—'}</dd>
          <dt>Role</dt><dd>{label(user.role)}</dd>
          {user.permissions?.length > 0 && <><dt>Permissions</dt><dd>{user.permissions.map(label).join(', ')}</dd></>}
          {user.managedLocations?.length > 0 && <><dt>Locations</dt><dd>{user.managedLocations.map((l) => l.name).join(', ')}</dd></>}
        </dl>
        <form onSubmit={(e) => { e.preventDefault(); a.run(() => patch('/me', p), (u) => { updateUser(u); setMsg('Profile saved.'); }); }}>
          <Field label="Phone"><input value={p.phone} onChange={(e) => setP({ ...p, phone: e.target.value })} /></Field>
          <Field label="Department"><input value={p.department} onChange={(e) => setP({ ...p, department: e.target.value })} /></Field>
          <ErrorBox error={a.error} />
          <button className="primary" disabled={a.busy}>Save</button> <span className="muted">{msg}</span>
        </form>
      </Card>
      <Card title="Change password">
        <form onSubmit={(e) => { e.preventDefault(); b.run(() => post('/me/password', pw), () => { setPw({ currentPassword: '', newPassword: '' }); setMsg('Password changed. Other sessions were signed out.'); }); }}>
          <Field label="Current password"><input type="password" value={pw.currentPassword} onChange={(e) => setPw({ ...pw, currentPassword: e.target.value })} /></Field>
          <Field label="New password"><input type="password" value={pw.newPassword} onChange={(e) => setPw({ ...pw, newPassword: e.target.value })} /></Field>
          <ErrorBox error={b.error} />
          <button className="primary" disabled={b.busy}>Change password</button>
        </form>
      </Card>
    </div>
  );
}
