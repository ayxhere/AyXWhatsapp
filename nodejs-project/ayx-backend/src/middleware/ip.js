// Client IP helper.
// Prefers Cloudflare's CF-Connecting-IP (set at the Cloudflare edge and
// overwritten there, so clients cannot spoof it through the tunnel), then
// falls back to req.ip — which is X-Forwarded-For-aware because the app sets
// `trust proxy` to 1 (see app.js). NOTE: the raw first X-Forwarded-For entry
// is deliberately NOT used: it is client-controlled and spoofable.

export function clientIp(req) {
  const cf = req.headers['cf-connecting-ip'];
  if (typeof cf === 'string' && cf.trim()) return cf.trim();
  return req.ip || 'unknown';
}

// Truncate an IP for audit storage (privacy: no full IPs retained).
// IPv4 -> "a.b.c.0" ; IPv6 -> first 4 hextets + "::".
export function truncateIp(ip) {
  if (!ip || typeof ip !== 'string') return null;
  const v = ip.trim();
  if (v.includes(':')) {
    const parts = v.split(':').filter(Boolean);
    return parts.slice(0, 4).join(':') + '::';
  }
  const parts = v.split('.');
  if (parts.length === 4) return `${parts[0]}.${parts[1]}.${parts[2]}.0`;
  return 'unknown';
}
