import { createContext, useContext, useEffect, useState } from 'react';
import { loadSession, saveSession, setLogoutHandler, post } from './api';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [session, setSession] = useState(loadSession);

  useEffect(() => { setLogoutHandler(() => setSession(null)); }, []);

  const signIn = (t) => {
    const s = { accessToken: t.accessToken, refreshToken: t.refreshToken, user: t.user };
    saveSession(s);
    setSession(s);
  };
  const signOut = async () => {
    const s = loadSession();
    if (s?.refreshToken) await post('/auth/logout', { refreshToken: s.refreshToken }).catch(() => {});
    saveSession(null);
    setSession(null);
  };
  const updateUser = (user) => {
    const s = { ...loadSession(), user };
    saveSession(s);
    setSession(s);
  };
  const user = session?.user || null;
  const can = (perm) => user?.role === 'ADMIN' || (user?.role === 'STAFF' && user.permissions?.includes(perm));
  return <AuthContext.Provider value={{ user, signIn, signOut, updateUser, can }}>{children}</AuthContext.Provider>;
}

export const useAuth = () => useContext(AuthContext);
