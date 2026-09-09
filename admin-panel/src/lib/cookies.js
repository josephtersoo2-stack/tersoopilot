export function parseCookies(raw) {
  const text = raw.replace(/^[\r\n]+|[\r\n]+$/g, "");
  if (text.trimStart().startsWith('[')) {
    const value = JSON.parse(text);
    if (!Array.isArray(value)) throw new Error('Expected a cookie array.');
    return value;
  }
  return text.split(/\r?\n/).flatMap((line) => {
    const httpOnly = line.startsWith('#HttpOnly_');
    if (!line.trim() || (line.startsWith('#') && !httpOnly)) return [];
    const parts = line.replace(/^#HttpOnly_/, '').split('\t');
    if (parts.length < 7) throw new Error('Invalid Netscape cookie row.');
    const expiry = Number(parts[4]);
    if (!Number.isFinite(expiry) || expiry < 0) throw new Error('Invalid cookie expiry.');
    return [{ domain: parts[0], path: parts[2], isSecure: parts[3].toUpperCase() === 'TRUE',
      isHttpOnly: httpOnly, expiry, name: parts[5], value: parts.slice(6).join('\t') }];
  });
}
