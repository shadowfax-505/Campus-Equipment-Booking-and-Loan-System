import { useCallback, useEffect, useState } from 'react';
import { label } from '../format';

/** Loads data from an async function; returns { data, error, loading, reload }. */
export function useLoad(fn, deps = []) {
  const [state, setState] = useState({ data: null, error: null, loading: true });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const run = useCallback(() => {
    setState((s) => ({ ...s, loading: true }));
    return fn().then((data) => setState({ data, error: null, loading: false }))
      .catch((error) => setState({ data: null, error, loading: false }));
  }, deps);
  useEffect(() => { run(); }, [run]);
  return { ...state, reload: run };
}

/** Runs an action with busy/error state; onDone is called with the result. */
export function useAction() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const run = async (fn, onDone) => {
    setBusy(true); setError(null);
    try { const r = await fn(); onDone && onDone(r); return r; }
    catch (e) { setError(e); return undefined; }
    finally { setBusy(false); }
  };
  return { busy, error, setError, run };
}

const tone = {
  PENDING: 'amber', WAITLISTED: 'violet', APPROVED: 'green', FULFILLED: 'blue', REJECTED: 'red', CANCELLED: 'gray', EXPIRED: 'gray',
  ACTIVE: 'green', RETURNED: 'blue', LOST: 'red', OVERDUE: 'red', UNDER_MAINTENANCE: 'amber', RETIRED: 'gray',
  OUTSTANDING: 'red', PARTIALLY_PAID: 'amber', PAID: 'green', WAIVED: 'gray',
  SUCCEEDED: 'green', FAILED: 'red', PENDING_VERIFICATION: 'amber',
  OPEN: 'amber', UNDER_REVIEW: 'violet', UPHELD: 'green', DISMISSED: 'gray', SUSPENDED: 'red', DEACTIVATED: 'gray',
  STUDENT: 'blue', STAFF: 'violet', ADMIN: 'red',
};
export const Badge = ({ value, children }) => <span className={`badge ${tone[value] || 'gray'}`}>{children || label(value)}</span>;

export const Card = ({ title, actions, children, className = '' }) => (
  <section className={`card ${className}`}>
    {(title || actions) && <header className="card-head"><h2>{title}</h2><div className="row">{actions}</div></header>}
    {children}
  </section>
);

export function ErrorBox({ error }) {
  if (!error) return null;
  return (
    <div className="error" role="alert">
      {error.message}
      {error.fields && <ul>{Object.entries(error.fields).map(([k, v]) => <li key={k}><b>{k}</b>: {v}</li>)}</ul>}
    </div>
  );
}

export function Loader({ state, children }) {
  if (state.loading && !state.data) return <p className="muted">Loading…</p>;
  if (state.error) return <ErrorBox error={state.error} />;
  return children(state.data);
}

export const Empty = ({ children }) => <p className="empty">{children}</p>;

export function Modal({ title, onClose, children }) {
  useEffect(() => {
    const k = (e) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', k);
    return () => window.removeEventListener('keydown', k);
  }, [onClose]);
  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-label={title}>
        <header className="card-head"><h2>{title}</h2><button className="ghost" onClick={onClose} aria-label="Close">✕</button></header>
        {children}
      </div>
    </div>
  );
}

export const Field = ({ label: l, children, hint }) => (
  <label className="field"><span>{l}</span>{children}{hint && <small className="muted">{hint}</small>}</label>
);

export const Stat = ({ label: l, value, tone: t }) => (
  <div className={`stat ${t || ''}`}><div className="stat-value">{value}</div><div className="stat-label">{l}</div></div>
);

export function ScoreRing({ score }) {
  const pct = Math.max(0, Math.min(100, score || 0));
  const color = pct >= 80 ? 'var(--green)' : pct >= 60 ? 'var(--amber)' : 'var(--red)';
  return (
    <div className="ring" style={{ background: `conic-gradient(${color} ${pct * 3.6}deg, var(--track) 0)` }}>
      <div className="ring-inner"><b>{pct.toFixed(1)}</b><small>of 100</small></div>
    </div>
  );
}
