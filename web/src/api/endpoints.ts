import { api, fileUrl } from './client';
import { deviceId, deviceName } from './session';
import type * as T from './types';

type Q = Record<string, string | number | boolean | undefined | null>;
const money = (amount: string | number) => Number(amount); // services take numbers, return strings

// ---------------------------------------------------------------- auth (01-auth.http)
export const authApi = {
  requestOtp: (mobile: string) => api<T.OtpResponse>('/auth/otp/request', { method: 'POST', body: { mobile }, anonymous: true }),
  verifyOtp: (mobile: string, otp: string, referralCode?: string) =>
    api<T.TokenResponse>('/auth/otp/verify', {
      method: 'POST',
      anonymous: true,
      body: { mobile, otp, deviceId: deviceId(), deviceName: deviceName(), referralCode: referralCode || undefined },
    }),
  me: () => api<T.MeResponse>('/auth/me'),
  setPin: (pin: string) => api<void>('/auth/pin', { method: 'POST', body: { pin } }),
  changePin: (currentPin: string, newPin: string) => api<void>('/auth/pin/change', { method: 'POST', body: { currentPin, newPin } }),
  requestPinReset: (mobile: string) => api<void>('/auth/pin/reset/request', { method: 'POST', body: { mobile }, anonymous: true }),
  confirmPinReset: (mobile: string, otp: string, newPin: string) =>
    api<void>('/auth/pin/reset/confirm', { method: 'POST', body: { mobile, otp, newPin }, anonymous: true }),
  devices: () => api<T.Device[]>('/auth/devices'),
  revokeDevice: (id: string) => api<void>(`/auth/devices/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  logout: (refreshToken: string) => api<void>('/auth/logout', { method: 'POST', body: { refreshToken } }),
};

// ---------------------------------------------------------------- user (02-user.http)
export const userApi = {
  profile: () => api<T.Profile>('/users/me'),
  updateProfile: (p: { fullName?: string; email?: string; dateOfBirth?: string }) => api<T.Profile>('/users/me', { method: 'PUT', body: p }),
  kyc: () => api<T.KycStatus>('/users/me/kyc'),
  minKyc: (k: { documentType: T.IdDocumentType; documentNumber: string; fullName: string; dateOfBirth: string }) =>
    api<T.KycRecord>('/users/me/kyc/min', { method: 'POST', body: k }),
  fullKyc: (aadhaarNumber: string, document: File) => {
    const form = new FormData();
    form.set('aadhaarNumber', aadhaarNumber);
    form.set('document', document);
    return api<T.KycRecord>('/users/me/kyc/full', { method: 'POST', body: form });
  },
  bankAccounts: () => api<T.BankAccount[]>('/users/me/bank-accounts'),
  linkBankAccount: (b: { accountNumber: string; ifsc: string; holderName: string }) =>
    api<T.BankAccount>('/users/me/bank-accounts', { method: 'POST', body: b }),
  makePrimary: (id: string) => api<T.BankAccount>(`/users/me/bank-accounts/${id}/primary`, { method: 'POST' }),
  unlinkBankAccount: (id: string) => api<void>(`/users/me/bank-accounts/${id}`, { method: 'DELETE' }),
  beneficiaries: () => api<T.Beneficiary[]>('/users/me/beneficiaries'),
  addBeneficiary: (b: { type: T.BeneficiaryType; value: string; ifsc?: string; nickname?: string }) =>
    api<T.Beneficiary>('/users/me/beneficiaries', { method: 'POST', body: b }),
  removeBeneficiary: (id: string) => api<void>(`/users/me/beneficiaries/${id}`, { method: 'DELETE' }),
};

// ---------------------------------------------------------------- wallet (03-wallet.http)
export const walletApi = {
  wallet: () => api<T.Wallet>('/wallets/me'),
  addMoney: (amount: string, paymentMethod: T.PaymentMethod, idempotencyKey: string) =>
    api<T.AddMoneyResponse>('/wallets/me/add-money', { method: 'POST', body: { amount: money(amount), paymentMethod }, idempotencyKey }),
  withdraw: (amount: string, bankAccountId: string, pin: string, idempotencyKey: string) =>
    api<T.WithdrawResponse>('/wallets/me/withdraw', { method: 'POST', body: { amount: money(amount), bankAccountId }, pin, idempotencyKey }),
  ledger: (page = 0, size = 20) => api<T.Page<T.LedgerEntry>>('/wallets/me/ledger', { query: { page, size } }),
};

// ---------------------------------------------------------------- transactions (04-transactions.http)
export interface HistoryFilter {
  direction?: 'SENT' | 'RECEIVED' | '';
  status?: T.TransactionStatus | '';
  from?: string;
  to?: string;
  minAmount?: string;
  maxAmount?: string;
  page?: number;
  size?: number;
}

export const transactionApi = {
  transfer: (payee: T.Payee, amount: string | undefined, note: string | undefined, pin: string, idempotencyKey: string) =>
    api<{ status: number; data: T.TransferResponse }>('/transfers', {
      method: 'POST',
      body: { payee, amount: amount ? money(amount) : undefined, note: note || undefined },
      pin,
      idempotencyKey,
      withStatus: true,
    }),
  transferStatus: (id: string) => api<T.TransferResponse>(`/transfers/${id}`),
  history: (f: HistoryFilter) =>
    api<T.Page<T.Transaction>>('/transactions', { query: { ...f, sort: 'createdAt', order: 'DESC' } as Q }),
  detail: (id: string) => api<T.TransactionDetail>(`/transactions/${id}`),
  statementUrl: (from: string, to: string | undefined, format: 'csv' | 'pdf') => fileUrl('/transactions/statement', { from, to, format }),
  requestMoney: (payer: T.Payee, amount: string, note?: string) =>
    api<T.MoneyRequest>('/money-requests', { method: 'POST', body: { payer, amount: money(amount), note: note || undefined } }),
  moneyRequests: (direction: 'INCOMING' | 'OUTGOING', page = 0) =>
    api<T.Page<T.MoneyRequest>>('/money-requests', { query: { direction, page, size: 50 } }),
  payRequest: (id: string, pin: string, idempotencyKey: string) =>
    api<T.TransferResponse>(`/money-requests/${id}/pay`, { method: 'POST', pin, idempotencyKey }),
  declineRequest: (id: string) => api<T.MoneyRequest>(`/money-requests/${id}/decline`, { method: 'POST' }),
  cancelRequest: (id: string) => api<T.MoneyRequest>(`/money-requests/${id}/cancel`, { method: 'POST' }),
  createSplit: (total: string, note: string | undefined, participants: T.Payee[], includeMe: boolean) =>
    api<T.SplitBill>('/split-bills', { method: 'POST', body: { total: money(total), note: note || undefined, includeMe, participants } }),
  split: (id: string) => api<T.SplitBill>(`/split-bills/${id}`),
};

// ---------------------------------------------------------------- payments (05-payments.http)
export const paymentApi = {
  myVpas: () => api<T.Vpa[]>('/vpa/me'),
  createVpa: (name: string) => api<T.Vpa>('/vpa', { method: 'POST', body: { name } }),
  resolveVpa: (vpa: string) => api<T.ResolvedVpa>(`/vpa/${encodeURIComponent(vpa)}/resolve`),
  staticQr: () => api<T.Qr>('/qr/static', { method: 'POST' }),
  dynamicQr: (amount: string, note: string | undefined, validityMinutes: number) =>
    api<T.Qr>('/qr/dynamic', { method: 'POST', body: { amount: money(amount), note: note || undefined, validityMinutes } }),
  qrImageUrl: (qr: T.Qr) => fileUrl(qr.imageUrl?.replace(/^\/api\/v1/, '') || `/qr/${qr.id}.png`),
  decodeQr: (payload: string) => api<T.DecodedQr>('/qr/decode', { method: 'POST', body: { payload } }),
  decodeQrImage: (image: File) => {
    const form = new FormData();
    form.set('image', image);
    return api<T.DecodedQr>('/qr/decode-image', { method: 'POST', body: form });
  },
  createCollect: (payerVpa: string, amount: string, note?: string) =>
    api<T.Collect>('/collect-requests', { method: 'POST', body: { payerVpa, amount: money(amount), note: note || undefined } }),
  collects: (direction: 'INCOMING' | 'OUTGOING') => api<T.Page<T.Collect>>('/collect-requests', { query: { direction, size: 50 } }),
  approveCollect: (id: string, pin: string, idempotencyKey: string) =>
    api<T.Collect>(`/collect-requests/${id}/approve`, { method: 'POST', pin, idempotencyKey }),
  rejectCollect: (id: string) => api<T.Collect>(`/collect-requests/${id}/reject`, { method: 'POST' }),
};

// ---------------------------------------------------------------- bills (08-bills.http)
export const billApi = {
  billers: (category?: T.BillCategory) => api<T.Biller[]>('/billers', { query: { category } }),
  fetch: (billerCode: string, accountRef: string) => api<T.FetchedBill>('/bills/fetch', { method: 'POST', body: { billerCode, accountRef } }),
  pay: (billerCode: string, accountRef: string, amount: string, pin: string, idempotencyKey: string) =>
    api<{ status: number; data: T.BillPayment }>('/bills/pay', {
      method: 'POST',
      body: { billerCode, accountRef, amount: money(amount) },
      pin,
      idempotencyKey,
      withStatus: true,
    }),
  payment: (id: string) => api<T.BillPayment>(`/bills/${id}`),
  history: (category?: T.BillCategory, page = 0) => api<T.Page<T.BillPayment>>('/bills/history', { query: { category, page, size: 20 } }),
  mandates: () => api<T.Mandate[]>('/mandates'),
  createMandate: (m: { billerCode: string; accountRef: string; frequency: T.MandateFrequency; maxAmount: string; amount?: string }, pin: string) =>
    api<T.Mandate>('/mandates', {
      method: 'POST',
      pin,
      body: { ...m, maxAmount: money(m.maxAmount), amount: m.amount ? money(m.amount) : undefined },
    }),
  cancelMandate: (id: string) => api<T.Mandate>(`/mandates/${id}`, { method: 'DELETE' }),
  executions: (id: string) => api<T.MandateExecution[]>(`/mandates/${id}/executions`),
};

// ---------------------------------------------------------------- rewards (07-rewards.http)
export const rewardsApi = {
  summary: () => api<T.RewardsSummary>('/rewards/me'),
  history: (page = 0) => api<T.Page<T.Reward>>('/rewards/history', { query: { page, size: 50 } }),
  scratchCards: () => api<T.Page<T.ScratchCard>>('/rewards/scratch-cards', { query: { size: 50 } }),
  scratch: (id: string) => api<T.ScratchCard>(`/rewards/scratch-cards/${id}/scratch`, { method: 'POST' }),
  coupons: () => api<T.Coupon[]>('/rewards/coupons'),
  redeem: (code: string) => api<T.Reward>(`/rewards/coupons/${encodeURIComponent(code)}/redeem`, { method: 'POST' }),
  referral: () => api<T.Referral>('/rewards/referral'),
};

// ---------------------------------------------------------------- notifications (06-notifications.http)
export const notificationApi = {
  inbox: (page = 0) => api<T.Inbox>('/notifications/me', { query: { page, size: 30 } }),
  markRead: (id: string) => api<T.Notification>(`/notifications/me/${id}/read`, { method: 'POST' }),
};

// ---------------------------------------------------------------- disputes (11-admin.http, user side)
export const disputeApi = {
  open: (transactionId: string, reason: string) => api<T.Dispute>('/disputes', { method: 'POST', body: { transactionId, reason } }),
  mine: () => api<T.Dispute[]>('/disputes'),
};

// ---------------------------------------------------------------- admin console (09/10/11 + admin parts of 06/07)
export const adminApi = {
  searchUsers: (q: { mobile?: string; name?: string; kycTier?: string }) => api<T.AdminUser[]>('/admin/users', { query: q }),
  user: (id: string) => api<T.AdminUser>(`/admin/users/${id}`),
  freeze: (userId: string, note: string) => api<T.AdminWallet>(`/admin/wallets/${userId}/freeze`, { method: 'POST', body: { note } }),
  unfreeze: (userId: string) => api<T.AdminWallet>(`/admin/wallets/${userId}/unfreeze`, { method: 'POST' }),
  rotateSigningKey: () => api<{ kid: string }>('/admin/security/signing-keys/rotate', { method: 'POST' }),

  disputes: (status?: string) => api<T.Page<T.Dispute>>('/admin/disputes', { query: { status, size: 100 } }),
  assignDispute: (id: string) => api<T.Dispute>(`/admin/disputes/${id}/assign`, { method: 'POST' }),
  resolveDispute: (id: string, outcome: 'REFUND' | 'REJECTED', note: string) =>
    api<T.Dispute>(`/admin/disputes/${id}/resolve`, { method: 'POST', body: { outcome, note } }),

  refunds: (status?: string) => api<T.Page<T.RefundRequest>>('/admin/refunds', { query: { status, size: 100 } }),
  requestRefund: (transactionId: string, reason: string) => api<T.RefundRequest>('/admin/refunds', { method: 'POST', body: { transactionId, reason } }),
  approveRefund: (id: string) => api<T.RefundRequest>(`/admin/refunds/${id}/approve`, { method: 'POST' }),
  rejectRefund: (id: string, note: string) => api<T.RefundRequest>(`/admin/refunds/${id}/reject`, { method: 'POST', body: { note } }),

  auditLogs: (q: { actor?: string; action?: string; entityType?: string; entityId?: string; page?: number }) =>
    api<T.AuditEntry[]>('/admin/audit-logs', { query: { ...q, size: 100 } }),

  riskFlags: (status = 'OPEN') => api<T.Page<T.RiskFlag>>('/admin/risk/flags', { query: { status, size: 100 } }),
  resolveFlag: (id: string, resolution: 'CONFIRMED_FRAUD' | 'FALSE_POSITIVE', note: string, freezeWallet: boolean) =>
    api<T.RiskFlag>(`/admin/risk/flags/${id}/resolve`, { method: 'POST', body: { resolution, note, freezeWallet } }),
  riskRules: () => api<T.RiskRule[]>('/admin/risk/rules'),
  updateRiskRule: (code: string, r: { weight: number; enabled: boolean; thresholdAmount?: number | null; thresholdCount?: number | null; windowSeconds?: number | null; multiplier?: number | null }) =>
    api<T.RiskRule>(`/admin/risk/rules/${code}`, { method: 'PUT', body: r }),

  runRecon: (job: T.ReconJob, date?: string) => api<T.ReconRun>(`/admin/recon/jobs/${job}/run`, { method: 'POST', query: { date } }),
  reconRuns: (job?: T.ReconJob) => api<T.Page<T.ReconRun>>('/admin/recon/runs', { query: { job, size: 50 } }),
  reconMismatches: (runId: string) => api<T.Page<T.ReconMismatch>>(`/admin/recon/runs/${runId}/mismatches`, { query: { size: 500 } }),
  openMismatches: () => api<T.Page<T.ReconMismatch>>('/admin/recon/mismatches', { query: { size: 200 } }),
  resolveMismatch: (id: string, note: string) => api<T.ReconMismatch>(`/admin/recon/mismatches/${id}/resolve`, { method: 'POST', body: { note } }),

  cashbackRules: () => api<T.CashbackRule[]>('/admin/cashback-rules'),
  coupons: () => api<T.Coupon[]>('/admin/coupons'),
  createCoupon: (c: { code: string; description: string; cashback: number; validUntil: string; usageLimit: number }) =>
    api<T.Coupon>('/admin/coupons', { method: 'POST', body: c }),
  setCouponActive: (code: string, active: boolean) => api<T.Coupon>(`/admin/coupons/${code}/status`, { method: 'PUT', body: { active } }),

  templates: () => api<T.Template[]>('/admin/templates'),
  updateTemplate: (code: string, channel: string, t: { subject?: string | null; body: string; active: boolean }) =>
    api<T.Template>(`/admin/templates/${code}/${channel}`, { method: 'PUT', body: t }),
  deadLetters: (onlyOpen = true) => api<T.Page<T.DeadLetter>>('/admin/dead-letters', { query: { onlyOpen, size: 100 } }),
  replayDeadLetter: (id: string) => api<T.DeadLetter>(`/admin/dead-letters/${id}/replay`, { method: 'POST' }),
};
