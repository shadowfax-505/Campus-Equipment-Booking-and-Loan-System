export const fmtDate = (s) => (s ? new Date(s).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }) : '—');
export const fmtDay = (s) => (s ? new Date(s).toLocaleDateString(undefined, { dateStyle: 'medium' }) : '—');
export const money = (v) => (v == null ? '—' : Number(v).toFixed(2));
export const label = (s) => (s ? String(s).replaceAll('_', ' ').toLowerCase().replace(/^\w/, (c) => c.toUpperCase()) : '');
export const toInput = (d) => {
  const x = new Date(d);
  const pad = (n) => String(n).padStart(2, '0');
  return `${x.getFullYear()}-${pad(x.getMonth() + 1)}-${pad(x.getDate())}T${pad(x.getHours())}:${pad(x.getMinutes())}`;
};
export const fromInput = (v) => (v ? new Date(v).toISOString() : null);
export const roundUpHour = (offsetHours = 1) => {
  const d = new Date();
  d.setMinutes(0, 0, 0);
  d.setHours(d.getHours() + offsetHours);
  return d;
};
