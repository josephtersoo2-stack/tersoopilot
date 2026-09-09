import React, { useEffect, useState } from 'react';
import API, { clearSession, getToken } from '../api';
import App from '../App';

export default function AuthGate() {
  const [user, setUser] = useState(null);
  const [checking, setChecking] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const verify = async () => {
    const { data } = await API.get('auth/me/');
    if (!data.is_staff) throw new Error('This dashboard requires a staff account.');
    setUser(data);
  };

  useEffect(() => {
    const expired = () => { setUser(null); setError('Please sign in to continue.'); };
    window.addEventListener('auth:expired', expired);
    (async () => {
      try { if (getToken()) await verify(); }
      catch (err) { setError(err.message); sessionStorage.removeItem('terso_token'); }
      finally { setChecking(false); }
    })();
    return () => window.removeEventListener('auth:expired', expired);
  }, []);

  const login = async (event) => {
    event.preventDefault();
    setBusy(true);
    setError('');
    const values = new FormData(event.currentTarget);
    try {
      const { data } = await API.post('auth/login/', {
        username: values.get('username'), password: values.get('password'),
      });
      sessionStorage.setItem('terso_token', data.token);
      await verify();
    } catch (err) {
      sessionStorage.removeItem('terso_token');
      setError(err.response?.data?.error || err.message);
    } finally { setBusy(false); }
  };

  const logout = async () => {
    try { await API.post('auth/logout/'); }
    catch { setError('Signed out locally. Server token revocation could not be confirmed.'); }
    finally { clearSession(); }
  };

  if (checking) return <div className="min-h-screen bg-[#0A0D14] text-white grid place-items-center">Checking session…</div>;
  if (user) return <App onLogout={logout} />;
  return (
    <main className="min-h-screen bg-[#0A0D14] text-white grid place-items-center p-6">
      <form onSubmit={login} className="w-full max-w-sm space-y-5 rounded-2xl border border-slate-700 bg-slate-900 p-8">
        <h1 className="text-2xl font-semibold">TersoPilot</h1>
        <p className="text-sm text-slate-400">Sign in to manage your browser fleet.</p>
        <label className="block text-sm">Username<input name="username" autoComplete="username" required className="mt-2 w-full rounded bg-slate-800 p-3" /></label>
        <label className="block text-sm">Password<input name="password" type="password" autoComplete="current-password" required className="mt-2 w-full rounded bg-slate-800 p-3" /></label>
        {error && <p role="alert" className="text-sm text-red-300">{error}</p>}
        <button disabled={busy} className="w-full rounded bg-blue-600 p-3 disabled:opacity-50">{busy ? 'Signing in…' : 'Sign in'}</button>
      </form>
    </main>
  );
}
