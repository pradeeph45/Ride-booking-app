// Small, pure helpers that turn raw API values into text people can read.

export interface DeviceLabel {
  label: string;
  icon: string;
}

const MAX_UNKNOWN = 40;

/** "Mozilla/5.0 (Windows NT 10.0...) Chrome/158..." -> "Chrome on Windows". */
export function describeDevice(userAgent: string | null | undefined): DeviceLabel {
  const ua = (userAgent ?? '').trim();
  if (!ua) return { label: 'Unknown device', icon: '❔' };

  if (/^(curl|wget|httpie|postman|java-http-client|okhttp|python-requests|axios|node)/i.test(ua)) {
    return { label: 'Command-line client', icon: '⌨️' };
  }

  // Order matters: Edge and Opera also contain "Chrome", Chrome also contains "Safari".
  const browser = /Edg\//.test(ua) ? 'Edge'
    : /OPR\/|Opera/.test(ua) ? 'Opera'
    : /Firefox\//.test(ua) ? 'Firefox'
    : /Chrome\//.test(ua) ? 'Chrome'
    : /Safari\//.test(ua) ? 'Safari'
    : null;

  const os = /Windows/.test(ua) ? 'Windows'
    : /Android/.test(ua) ? 'Android'
    : /iPhone|iPad|iPod/.test(ua) ? 'iOS'
    : /Mac OS X|Macintosh/.test(ua) ? 'macOS'
    : /CrOS/.test(ua) ? 'ChromeOS'
    : /Linux/.test(ua) ? 'Linux'
    : null;

  const mobile = os === 'Android' || os === 'iOS';
  const icon = mobile ? '📱' : '💻';

  if (browser && os) return { label: `${browser} on ${os}`, icon };
  if (browser) return { label: browser, icon };
  if (os) return { label: `Device on ${os}`, icon };
  return { label: ua.length > MAX_UNKNOWN ? `${ua.slice(0, MAX_UNKNOWN)}…` : ua, icon: '❔' };
}

/** "5 min ago", "3 h ago", "2 days ago", or a short date for anything older than a week. */
export function timeAgo(iso: string, now: Date = new Date()): string {
  const seconds = Math.max(0, Math.floor((now.getTime() - new Date(iso).getTime()) / 1000));
  if (seconds < 45) return 'just now';
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  const days = Math.floor(hours / 24);
  if (days < 7) return days === 1 ? '1 day ago' : `${days} days ago`;
  return new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
}

/** "demo.rider@example.com" -> "DR" (first letters of the name part, up to two). */
export function initialsOf(email: string): string {
  const name = email.split('@')[0] ?? '';
  const parts = name.split(/[._-]+/).filter(Boolean);
  if (parts.length === 0) return '?';
  return parts.slice(0, 2).map((p) => p[0].toUpperCase()).join('');
}

export function roleLabel(role: string): string {
  return role.charAt(0) + role.slice(1).toLowerCase();
}

/** "12 Nov, 4:05 pm" style, without seconds. */
export function shortDateTime(iso: string): string {
  return new Date(iso).toLocaleString('en-GB', { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit', hour12: true });
}
