import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from 'react';
import { Button, Modal } from './ui';

// ---------------------------------------------------------------- toasts
type Toast = { id: number; tone: 'success' | 'danger' | 'info'; text: string };
const ToastContext = createContext<(text: string, tone?: Toast['tone']) => void>(() => {});

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const next = useRef(1);
  const show = useCallback((text: string, tone: Toast['tone'] = 'success') => {
    const id = next.current++;
    setToasts((t) => [...t, { id, tone, text }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 5000);
  }, []);
  return (
    <ToastContext.Provider value={show}>
      {children}
      <div className="toasts" aria-live="polite">
        {toasts.map((t) => <div key={t.id} className={`toast toast-${t.tone}`}>{t.text}</div>)}
      </div>
    </ToastContext.Provider>
  );
}

export const useToast = () => useContext(ToastContext);

// ---------------------------------------------------------------- transaction PIN prompt
type PinRequest = { title: string; summary?: ReactNode; resolve: (pin: string | null) => void };
const PinContext = createContext<(title: string, summary?: ReactNode) => Promise<string | null>>(async () => null);

/** `const pin = await askPin('Pay ₹250 to ravi@payverse')` — resolves null when cancelled. The PIN never leaves memory. */
export function PinProvider({ children }: { children: ReactNode }) {
  const [request, setRequest] = useState<PinRequest | null>(null);
  const [pin, setPin] = useState('');
  const ask = useCallback((title: string, summary?: ReactNode) =>
    new Promise<string | null>((resolve) => {
      setPin('');
      setRequest({ title, summary, resolve });
    }), []);
  const finish = (value: string | null) => {
    request?.resolve(value);
    setRequest(null);
    setPin('');
  };
  const valid = /^\d{4}$|^\d{6}$/.test(pin);
  return (
    <PinContext.Provider value={ask}>
      {children}
      <Modal title="Enter your transaction PIN" open={request !== null} onClose={() => finish(null)}
             footer={<><Button variant="ghost" onClick={() => finish(null)}>Cancel</Button><Button form="pin-form" type="submit" disabled={!valid}>Confirm</Button></>}>
        <form id="pin-form" onSubmit={(e) => { e.preventDefault(); if (valid) finish(pin); }}>
          <p className="pin-title">{request?.title}</p>
          {request?.summary && <div className="muted">{request.summary}</div>}
          <input className="input pin-input" type="password" inputMode="numeric" autoComplete="off" autoFocus maxLength={6}
                 aria-label="Transaction PIN" placeholder="••••" value={pin} onChange={(e) => setPin(e.target.value.replace(/\D/g, ''))} />
          <p className="field-hint">4 or 6 digits. 5 wrong attempts lock the PIN.</p>
        </form>
      </Modal>
    </PinContext.Provider>
  );
}

export const usePin = () => useContext(PinContext);
