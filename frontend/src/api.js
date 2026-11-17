// Thin fetch wrapper: attaches the access token, refreshes it once on 401, and turns API errors into exceptions.
const KEY = 'campus-equipment-session';

export function loadSession() {
  try { return JSON.parse(localStorage.getItem(KEY)) || null; } catch { return null; }
}
export function saveSession(s) {
  try { s ? localStorage.setItem(KEY, JSON.stringify(s)) : localStorage.removeItem(KEY); } catch { /* storage unavailable */ }
}

let onLogout = () => {};
export function setLogoutHandler(fn) { onLogout = fn; }

export class ApiError extends Error {
  constructor(status, body) {
    super(body?.message || `Request failed (${status})`);
    this.status = status;
    this.code = body?.code;
    this.fields = body?.fields;
  }
}

let refreshing = null;
async function refresh() {
  const s = loadSession();
  if (!s?.refreshToken) throw new ApiError(401, { message: 'Not signed in' });
  if (!refreshing) {
    refreshing = fetch('/api/auth/refresh', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: s.refreshToken }),
    }).then(async (r) => {
      if (!r.ok) throw new ApiError(r.status, await r.json().catch(() => null));
      const t = await r.json();
      saveSession({ ...s, accessToken: t.accessToken, refreshToken: t.refreshToken, user: t.user });
      return t;
    }).finally(() => { refreshing = null; });
  }
  return refreshing;
}

export async function api(path, { method = 'GET', body, retry = true } = {}) {
  const s = loadSession();
  const headers = { 'Content-Type': 'application/json' };
  if (s?.accessToken) headers.Authorization = `Bearer ${s.accessToken}`;
  const res = await fetch(`/api${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  if (res.status === 401 && retry && s?.refreshToken && !path.startsWith('/auth/')) {
    try { await refresh(); } catch { saveSession(null); onLogout(); throw new ApiError(401, { message: 'Session expired' }); }
    return api(path, { method, body, retry: false });
  }
  if (res.status === 204) return null;
  const data = await res.json().catch(() => null);
  if (!res.ok) throw new ApiError(res.status, data);
  return data;
}

export const get = (p) => api(p);
export const post = (p, body = {}) => api(p, { method: 'POST', body });
export const put = (p, body) => api(p, { method: 'PUT', body });
export const patch = (p, body) => api(p, { method: 'PATCH', body });
