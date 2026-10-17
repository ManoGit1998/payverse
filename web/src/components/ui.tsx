import { useEffect, useId, useRef, type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes } from 'react';
import { errorMessage } from '../api/errors';
import { formatMoney } from '../lib/format';
import type { Money } from '../api/types';

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger';

export function Button({ variant = 'primary', busy, children, className = '', ...rest }:
  ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; busy?: boolean }) {
  return (
    <button className={`btn btn-${variant} ${className}`} disabled={busy || rest.disabled} aria-busy={busy || undefined} {...rest}>
      {busy && <span className="spinner" aria-hidden />}
      <span>{children}</span>
    </button>
  );
}

export function Field({ label, hint, error, children }: { label: string; hint?: ReactNode; error?: string | null; children: (id: string) => ReactNode }) {
  const id = useId();
  return (
    <div className={`field ${error ? 'field-error' : ''}`}>
      <label htmlFor={id}>{label}</label>
      {children(id)}
      {error ? <p className="field-message" role="alert">{error}</p> : hint ? <p className="field-hint">{hint}</p> : null}
    </div>
  );
}

export function TextInput({ label, hint, error, ...rest }: InputHTMLAttributes<HTMLInputElement> & { label: string; hint?: ReactNode; error?: string | null }) {
  return <Field label={label} hint={hint} error={error}>{(id) => <input id={id} className="input" {...rest} />}</Field>;
}

export function Select({ label, hint, children, ...rest }: SelectHTMLAttributes<HTMLSelectElement> & { label: string; hint?: ReactNode }) {
  return (
    <Field label={label} hint={hint}>
      {(id) => <select id={id} className="input" {...rest}>{children}</select>}
    </Field>
  );
}

export function AmountInput({ label = 'Amount', value, onChange, hint, error, autoFocus, disabled }:
  { label?: string; value: string; onChange: (v: string) => void; hint?: ReactNode; error?: string | null; autoFocus?: boolean; disabled?: boolean }) {
  return (
    <Field label={label} hint={hint} error={error}>
      {(id) => (
        <div className="amount-input">
          <span aria-hidden>₹</span>
          <input id={id} inputMode="decimal" placeholder="0.00" value={value} autoFocus={autoFocus} disabled={disabled}
                 onChange={(e) => onChange(e.target.value.replace(/[^\d.]/g, '').replace(/(\..*)\./g, '$1').replace(/(\.\d{2}).+/, '$1'))} />
        </div>
      )}
    </Field>
  );
}

export function Card({ title, action, children, className = '' }: { title?: ReactNode; action?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={`card ${className}`}>
      {(title || action) && (
        <header className="card-header">
          {title && <h2>{title}</h2>}
          {action}
        </header>
      )}
      {children}
    </section>
  );
}

export function PageHeader({ title, subtitle, action }: { title: string; subtitle?: ReactNode; action?: ReactNode }) {
  useEffect(() => {
    document.title = `${title} · PayVerse`;
  }, [title]);
  return (
    <div className="page-header">
      <div>
        <h1>{title}</h1>
        {subtitle && <p className="muted">{subtitle}</p>}
      </div>
      {action}
    </div>
  );
}

const TONE: Record<string, string> = {
  SUCCESS: 'success', COMPLETED: 'success', PAID: 'success', APPROVED: 'success', CREDITED: 'success', ACTIVE: 'success', SENT: 'success',
  VERIFIED: 'success', SCRATCHED: 'success', RESOLVED: 'success', REWARDED: 'success', FALSE_POSITIVE: 'success', ALLOW: 'success',
  PENDING: 'warning', PROCESSING: 'warning', INITIATED: 'warning', REQUESTED: 'warning', IN_REVIEW: 'warning', OPEN: 'warning',
  RUNNING: 'warning', PENDING_CREDIT: 'warning', UNSCRATCHED: 'info', REVIEW: 'warning', RETRY: 'warning',
  FAILED: 'danger', DECLINED: 'danger', REJECTED: 'danger', FROZEN: 'danger', DEAD: 'danger', BLOCK: 'danger', CONFIRMED_FRAUD: 'danger',
  CLAWBACK_FAILED: 'danger', SKIPPED: 'neutral', EXPIRED: 'neutral', CANCELLED: 'neutral', VOIDED: 'neutral', REVERSED: 'info',
  CLAWED_BACK: 'neutral', NONE: 'neutral', MIN_KYC: 'info', FULL_KYC: 'success',
};

export function Badge({ value, tone }: { value: string; tone?: string }) {
  return <span className={`badge badge-${tone ?? TONE[value] ?? 'neutral'}`}>{value.replace(/_/g, ' ').toLowerCase()}</span>;
}

export function Amount({ value, sign, className = '' }: { value: Money | string | number; sign?: '+' | '-'; className?: string }) {
  return <span className={`amount ${sign === '+' ? 'amount-in' : sign === '-' ? 'amount-out' : ''} ${className}`}>{sign}{formatMoney(value)}</span>;
}

export function ErrorBanner({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  if (!error) return null;
  return (
    <div className="banner banner-danger" role="alert">
      <span>{errorMessage(error)}</span>
      {onRetry && <button className="link" onClick={onRetry}>Try again</button>}
    </div>
  );
}

export function Banner({ tone = 'info', children }: { tone?: 'info' | 'warning' | 'success' | 'danger'; children: ReactNode }) {
  return <div className={`banner banner-${tone}`}>{children}</div>;
}

export function Spinner({ label = 'Loading…' }: { label?: string }) {
  return <div className="loading" role="status"><span className="spinner" aria-hidden /> {label}</div>;
}

export function Empty({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="empty">
      <p className="empty-title">{title}</p>
      {children && <div className="muted">{children}</div>}
    </div>
  );
}

export function Tabs<T extends string>({ value, onChange, options }: { value: T; onChange: (v: T) => void; options: { value: T; label: ReactNode }[] }) {
  return (
    <div className="tabs" role="tablist">
      {options.map((o) => (
        <button key={o.value} role="tab" aria-selected={value === o.value} className={value === o.value ? 'tab active' : 'tab'} onClick={() => onChange(o.value)}>
          {o.label}
        </button>
      ))}
    </div>
  );
}

export function Modal({ title, open, onClose, children, footer }: { title: string; open: boolean; onClose: () => void; children: ReactNode; footer?: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const previous = document.activeElement as HTMLElement | null;
    const focusable = ref.current?.querySelector<HTMLElement>('[autofocus], input, select, textarea, button:not(.icon-btn)');
    focusable?.focus();
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      previous?.focus?.();
    };
  }, [open, onClose]);
  if (!open) return null;
  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div ref={ref} className="modal" role="dialog" aria-modal="true" aria-label={title}>
        <header className="modal-header">
          <h2>{title}</h2>
          <button className="icon-btn" aria-label="Close" onClick={onClose}>✕</button>
        </header>
        <div className="modal-body">{children}</div>
        {footer && <footer className="modal-footer">{footer}</footer>}
      </div>
    </div>
  );
}

export function KeyValue({ items }: { items: [string, ReactNode][] }) {
  return (
    <dl className="kv">
      {items.map(([k, v]) => (
        <div key={k}><dt>{k}</dt><dd>{v ?? '—'}</dd></div>
      ))}
    </dl>
  );
}

export function Pager({ page, totalPages, onChange }: { page: number; totalPages?: number; onChange: (p: number) => void }) {
  if (!totalPages || totalPages <= 1) return null;
  return (
    <div className="pager">
      <Button variant="ghost" disabled={page === 0} onClick={() => onChange(page - 1)}>← Newer</Button>
      <span className="muted">Page {page + 1} of {totalPages}</span>
      <Button variant="ghost" disabled={page + 1 >= totalPages} onClick={() => onChange(page + 1)}>Older →</Button>
    </div>
  );
}
