import { useState } from 'react';
import { post } from '../api';
import { useAuth } from '../auth';
import { ErrorBox, Field, useAction } from '../components/ui';

const DEMO = [
  ['Student (high score)', 'alice@student.campus.edu', 'Student123'],
  ['Student (one late return)', 'bilal@student.campus.edu', 'Student123'],
  ['Lab staff, Media Lab', 'staff.media@campus.edu', 'Staff12345'],
  ['Lab staff, CSE + EEE (all permissions)', 'staff.lab@campus.edu', 'Staff12345'],
  ['Administrator', 'admin@campus.edu', 'Admin12345'],
];

export default function Login() {
  const { signIn } = useAuth();
  const [mode, setMode] = useState('login');
  const [f, setF] = useState({ email: '', password: '', fullName: '', institutionId: '', department: '' });
  const a = useAction();
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value });

  const submit = (e) => {
    e.preventDefault();
    a.run(() => (mode === 'login'
      ? post('/auth/login', { email: f.email, password: f.password })
      : post('/auth/register', f)), signIn);
  };

  return (
    <div className="login">
      <form className="card login-card" onSubmit={submit}>
        <div className="brand big">Campus<span>Equipment</span></div>
        <p className="muted">Book, borrow and return lab equipment.</p>
        <div className="tabs">
          <button type="button" className={mode === 'login' ? 'on' : ''} onClick={() => setMode('login')}>Sign in</button>
          <button type="button" className={mode === 'register' ? 'on' : ''} onClick={() => setMode('register')}>Register as student</button>
        </div>
        {mode === 'register' && <>
          <Field label="Full name"><input required value={f.fullName} onChange={set('fullName')} /></Field>
          <Field label="Student ID"><input value={f.institutionId} onChange={set('institutionId')} /></Field>
          <Field label="Department"><input value={f.department} onChange={set('department')} /></Field>
        </>}
        <Field label="Email"><input type="email" required autoComplete="username" value={f.email} onChange={set('email')} /></Field>
        <Field label="Password" hint={mode === 'register' ? 'At least 8 characters with a letter and a digit' : null}>
          <input type="password" required autoComplete={mode === 'login' ? 'current-password' : 'new-password'} value={f.password} onChange={set('password')} />
        </Field>
        <ErrorBox error={a.error} />
        <button className="primary" disabled={a.busy}>{a.busy ? 'Please wait…' : mode === 'login' ? 'Sign in' : 'Create account'}</button>
        <details className="demo">
          <summary>Demo accounts</summary>
          {DEMO.map(([l, e, p]) => (
            <button type="button" key={e} className="link" onClick={() => { setMode('login'); setF({ ...f, email: e, password: p }); }}>
              {l}: <code>{e}</code>
            </button>
          ))}
        </details>
      </form>
    </div>
  );
}
