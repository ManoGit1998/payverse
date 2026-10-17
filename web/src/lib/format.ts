import type { Money } from '../api/types';

const inr = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', minimumFractionDigits: 2 });

/** "1234.5" | 1234.5 | {amount:"1234.50"} → "₹1,234.50" (Indian digit grouping). */
export function formatMoney(value: Money | string | number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  const raw = typeof value === 'object' ? value.amount : value;
  const n = Number(raw);
  return Number.isFinite(n) ? inr.format(n) : String(raw);
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return d.toLocaleString('en-IN', { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
}

export function formatDate(iso: string | null | undefined): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}

export function relativeTime(iso: string, now: Date = new Date()): string {
  const seconds = Math.round((now.getTime() - new Date(iso).getTime()) / 1000);
  if (seconds < 45) return 'just now';
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  const days = Math.round(hours / 24);
  return days === 1 ? 'yesterday' : days < 7 ? `${days} days ago` : formatDate(iso);
}

/** WALLET_INSUFFICIENT_BALANCE → "Insufficient balance"; TRANSFER_DEBIT → "Transfer debit". */
export function humanize(code: string | null | undefined): string {
  if (!code) return '';
  const words = code.replace(/^(WALLET|TXN|AUTH|USER|PAYMENT|BILL|REWARDS|NOTIFICATION|FRAUD|RECON|ADMIN)_/, '').toLowerCase().split('_');
  const text = words.join(' ');
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** Amount input: digits with up to 2 decimals. */
export function isValidAmount(value: string, min = 1, max = 10_000_000): boolean {
  if (!/^\d+(\.\d{1,2})?$/.test(value.trim())) return false;
  const n = Number(value);
  return n >= min && n <= max;
}

export function initials(name: string | null | undefined): string {
  if (!name) return '•';
  const parts = name.replace(/\(.*\)/, '').trim().split(/\s+/).filter(Boolean);
  return ((parts[0]?.[0] ?? '') + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase() || '•';
}
