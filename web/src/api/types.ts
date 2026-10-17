// Wire types of the PayVerse gateway API (mirrors the services' DTO records). Money is a string ("100.00").

export interface Money {
  amount: string;
  currency: string;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems?: number;
  totalElements?: number;
  totalPages?: number;
}

// ---------- auth-service
export interface OtpResponse { expiresAt: string; expiresInSeconds: number; message: string }
export interface TokenResponse {
  tokenType: string;
  accessToken: string;
  expiresIn: number;
  refreshToken: string;
  refreshExpiresIn: number;
  userId: string;
  newUser: boolean;
  pinSet: boolean;
}
export interface MeResponse { userId: string; roles: string[]; deviceId: string; pinSet: boolean }
export interface Device { deviceId: string; name: string; boundAt: string; lastSeenAt: string; current: boolean }

// ---------- user-service
export type KycTier = 'NONE' | 'MIN_KYC' | 'FULL_KYC';
export type IdDocumentType = 'PAN' | 'VOTER_ID' | 'DRIVING_LICENSE' | 'PASSPORT' | 'AADHAAR';
export interface Profile {
  userId: string;
  fullName: string | null;
  email: string | null;
  dateOfBirth: string | null;
  mobileMasked: string;
  kycTier: KycTier;
  createdAt: string;
  updatedAt: string;
}
export interface KycRecord {
  id: string;
  tier: KycTier;
  documentType: IdDocumentType;
  documentMasked: string;
  status: 'VERIFIED' | 'REJECTED';
  reason: string | null;
  createdAt: string;
}
export interface KycStatus { tier: KycTier; nextTier: KycTier | null; history: KycRecord[] }
export interface BankAccount {
  id: string;
  maskedAccountNumber: string;
  ifsc: string;
  bankCode: string;
  holderName: string;
  primary: boolean;
  createdAt: string;
}
export type BeneficiaryType = 'MOBILE' | 'VPA' | 'BANK_ACCOUNT';
export interface Beneficiary { id: string; type: BeneficiaryType; value: string; ifsc: string | null; nickname: string | null; createdAt: string }

// ---------- wallet-service
export type WalletStatus = 'ACTIVE' | 'FROZEN';
export interface Limits { maxBalance: string; monthlyLoadLimit: string; monthlyLoadUsed: string; dailyDebitLimit: string; dailyDebitUsed: string }
export interface Wallet {
  walletId: string;
  userId: string;
  balance: Money;
  status: WalletStatus;
  statusReason: string | null;
  kycTier: KycTier;
  limits: Limits;
  updatedAt: string;
}
export type PaymentMethod = 'UPI' | 'CARD' | 'NETBANKING';
export interface AddMoneyResponse { journalId: string; amount: Money; balance: Money; paymentReference: string; status: string; createdAt: string }
export interface WithdrawResponse {
  journalId: string;
  amount: Money;
  balance: Money;
  bankAccountId: string;
  maskedAccountNumber: string;
  payoutReference: string;
  status: string;
  createdAt: string;
}
export type JournalType = 'ADD_MONEY' | 'WITHDRAWAL' | 'TRANSFER_DEBIT' | 'TRANSFER_CREDIT' | 'BILL_PAYMENT' | 'BILL_REFUND' | 'CASHBACK' | 'REVERSAL';
export interface LedgerEntry {
  entryId: string;
  journalId: string;
  type: JournalType;
  side: 'DEBIT' | 'CREDIT';
  amount: Money;
  balanceAfter: Money;
  referenceType: string;
  referenceId: string;
  description: string | null;
  createdAt: string;
}

// ---------- transaction-service
export type PayeeType = 'MOBILE' | 'VPA' | 'QR' | 'USER';
export type TransactionStatus = 'INITIATED' | 'PENDING' | 'SUCCESS' | 'FAILED' | 'REVERSED';
export type TransferSource = 'DIRECT' | 'MONEY_REQUEST' | 'COLLECT';
export interface Payee { type: PayeeType; value: string }
export interface TransferResponse {
  transactionId: string;
  status: TransactionStatus;
  amount: Money;
  payee: { type: PayeeType; ref: string; name: string | null };
  note: string | null;
  failureCode: string | null;
  failureReason: string | null;
  source: TransferSource;
  createdAt: string;
  completedAt: string | null;
}
export interface Transaction {
  id: string;
  direction: 'SENT' | 'RECEIVED';
  counterparty: string;
  payeeType: PayeeType;
  amount: Money;
  status: TransactionStatus;
  failureCode: string | null;
  note: string | null;
  source: TransferSource;
  createdAt: string;
  completedAt: string | null;
}
export interface StatusChange { from: TransactionStatus | null; to: TransactionStatus; reason: string | null; at: string }
export interface TransactionDetail { transaction: Transaction; statusHistory: StatusChange[] }
export type MoneyRequestStatus = 'PENDING' | 'PAID' | 'DECLINED' | 'CANCELLED' | 'EXPIRED';
export interface MoneyRequest {
  id: string;
  direction: 'INCOMING' | 'OUTGOING';
  counterparty: string;
  amount: Money;
  note: string | null;
  status: MoneyRequestStatus;
  expiresAt: string;
  transactionId: string | null;
  splitBillId: string | null;
  createdAt: string;
}
export interface SplitShare { moneyRequestId: string; participant: string; amount: Money; status: MoneyRequestStatus; transactionId: string | null }
export interface SplitBill { id: string; total: Money; creatorShare: Money; collected: Money; note: string | null; shares: SplitShare[]; createdAt: string }

// ---------- payment-service
export interface Vpa { id: string; vpa: string; defaultVpa: boolean; status: string; createdAt: string }
export interface ResolvedVpa { vpa: string; payeeName: string }
export type QrType = 'STATIC' | 'DYNAMIC';
export interface Qr { id: string; type: QrType; vpa: string; amount: Money | null; note: string | null; payload: string; imageUrl: string; expiresAt: string | null; createdAt: string }
export interface DecodedQr { qrId: string | null; vpa: string; payeeName: string | null; amount: Money | null; note: string | null; type: QrType; expiresAt: string | null }
export type CollectStatus = 'PENDING' | 'PROCESSING' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | 'FAILED';
export interface Collect {
  id: string;
  payerVpa: string;
  payeeVpa: string;
  amount: Money;
  note: string | null;
  status: CollectStatus;
  expiresAt: string;
  transactionId: string | null;
  createdAt: string;
}

// ---------- bill-payment-service
export type BillCategory = 'MOBILE' | 'ELECTRICITY' | 'DTH' | 'FASTAG' | 'CREDIT_CARD';
export interface Biller {
  code: string;
  category: BillCategory;
  name: string;
  accountLabel: string;
  accountPattern: string;
  fetchSupported: boolean;
  minAmount: number;
  maxAmount: number;
}
export interface FetchedBill { billerCode: string; accountRefMasked: string; customerName: string; billNumber: string; amountDue: Money; dueDate: string }
export interface BillPayment {
  id: string;
  billerCode: string;
  category: BillCategory;
  accountRefMasked: string;
  amount: Money;
  status: 'PENDING' | 'SUCCESS' | 'FAILED';
  billerRef: string | null;
  refunded: boolean;
  failureCode: string | null;
  failureReason: string | null;
  source: 'DIRECT' | 'MANDATE';
  mandateId: string | null;
  createdAt: string;
  completedAt: string | null;
}
export type MandateFrequency = 'WEEKLY' | 'MONTHLY';
export interface Mandate {
  id: string;
  billerCode: string;
  accountRefMasked: string;
  frequency: MandateFrequency;
  maxAmount: Money;
  amount: Money | null;
  status: 'ACTIVE' | 'CANCELLED';
  nextRunAt: string;
  runCount: number;
  createdAt: string;
  cancelledAt: string | null;
}
export interface MandateExecution {
  id: string;
  scheduledFor: string;
  billPaymentId: string | null;
  amount: number | null;
  status: 'PENDING' | 'SUCCESS' | 'FAILED' | 'SKIPPED';
  reason: string | null;
  completedAt: string | null;
}

// ---------- rewards-service
export interface Reward {
  id: string;
  ruleCode: string;
  sourceType: string;
  sourceId: string;
  amount: Money;
  status: 'PENDING_CREDIT' | 'CREDITED' | 'FAILED' | 'CLAWED_BACK' | 'CLAWBACK_FAILED' | 'VOIDED';
  createdAt: string;
  creditedAt: string | null;
}
export interface RewardsSummary { totalCredited: Money; pendingCredit: Money; recent: Reward[] }
export interface ScratchCard {
  id: string;
  status: 'UNSCRATCHED' | 'SCRATCHED' | 'EXPIRED' | 'VOIDED';
  amount: Money | null;
  ruleCode: string;
  sourceType: string;
  expiresAt: string;
  scratchedAt: string | null;
  rewardId: string | null;
  createdAt: string;
}
export interface Coupon {
  id: string;
  code: string;
  description: string;
  cashback: Money;
  validFrom: string;
  validUntil: string;
  usageLimit: number;
  usedCount: number;
  active: boolean;
}
export interface Referral { code: string; referred: number; rewarded: number; referrerBonus: Money; refereeBonus: Money }

// ---------- notification-service
export interface Notification {
  id: string;
  templateCode: string;
  channel: 'SMS' | 'EMAIL' | 'PUSH';
  subject: string | null;
  body: string;
  status: 'PENDING' | 'SENT' | 'DEAD';
  attempts: number;
  createdAt: string;
  sentAt: string | null;
  readAt: string | null;
}
export interface Inbox { items: Notification[]; unread: number; page: number; size: number; totalElements: number }

// ---------- admin-service
export interface Dispute {
  id: string;
  transactionId: string;
  userId: string;
  reason: string;
  status: 'OPEN' | 'IN_REVIEW' | 'RESOLVED';
  outcome: 'REFUND' | 'REJECTED' | null;
  assignee: string | null;
  resolutionNote: string | null;
  refundId: string | null;
  createdAt: string;
  resolvedAt: string | null;
}
export type RefundStatus = 'REQUESTED' | 'APPROVED' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'REJECTED';
export interface RefundRequest {
  id: string;
  transactionId: string;
  amount: string;
  currency: string;
  reason: string;
  disputeId: string | null;
  requestedBy: string;
  decidedBy: string | null;
  status: RefundStatus;
  failureCode: string | null;
  attempts: number;
  createdAt: string;
  decidedAt: string | null;
  completedAt: string | null;
}
export interface AdminWallet { walletId: string; userId: string; balance: Money; status: WalletStatus; statusReason: string | null; kycTier: KycTier }
export interface AdminUser { userId: string; fullName: string | null; mobileMasked: string | null; kycTier: KycTier | null; wallet: AdminWallet | null }
export interface AuditEntry { id: string; actor: string; action: string; entityType: string; entityId: string; details: string; correlationId: string | null; createdAt: string }

// fraud-risk-service
export interface RiskFlag {
  id: string;
  assessmentId: string;
  transactionId: string;
  userId: string;
  decision: 'REVIEW' | 'BLOCK';
  score: number;
  reasons: string;
  status: 'OPEN' | 'CONFIRMED_FRAUD' | 'FALSE_POSITIVE';
  resolvedBy: string | null;
  resolutionNote: string | null;
  walletFrozen: boolean;
  createdAt: string;
  resolvedAt: string | null;
}
export interface RiskRule {
  code: string;
  description: string;
  weight: number;
  enabled: boolean;
  thresholdAmount: number | null;
  thresholdCount: number | null;
  windowSeconds: number | null;
  multiplier: number | null;
  updatedAt: string;
}

// reconciliation-service
export type ReconJob = 'PG_VS_LEDGER' | 'TRANSFERS_VS_UPI' | 'WALLET_INTEGRITY';
export interface ReconRun {
  id: string;
  job: ReconJob;
  businessDate: string;
  windowFrom: string;
  windowTo: string;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED';
  triggeredBy: string;
  leftCount: number;
  rightCount: number;
  matchedCount: number;
  mismatchCount: number;
  error: string | null;
  startedAt: string;
  finishedAt: string | null;
}
export interface ReconMismatch {
  id: string;
  runId: string;
  job: ReconJob;
  type: string;
  reference: string;
  expectedAmount: number | null;
  actualAmount: number | null;
  status: 'OPEN' | 'RESOLVED';
  note: string | null;
  resolvedBy: string | null;
  createdAt: string;
}

// rewards admin
export interface CashbackRule {
  id: string;
  code: string;
  name: string;
  trigger: 'P2P_TRANSFER' | 'ADD_MONEY' | 'BILL_PAYMENT';
  rewardType: 'SCRATCH_CARD' | 'FLAT' | 'PERCENT';
  flatAmount: number | null;
  percent: number | null;
  minReward: number | null;
  maxReward: number | null;
  minTxnAmount: number | null;
  maxTxnAmount: number | null;
  firstTimeOnly: boolean;
  dailyCapPerUser: number | null;
  budget: number | null;
  spent: number;
  priority: number;
  active: boolean;
}

// notification admin
export interface Template { id: string; code: string; channel: 'SMS' | 'EMAIL' | 'PUSH'; subject: string | null; body: string; active: boolean; updatedAt: string }
export interface DeadLetter {
  id: string;
  dltTopic: string;
  originalTopic: string;
  partition: number;
  offset: number;
  messageKey: string | null;
  payload: string;
  errorMessage: string | null;
  receivedAt: string;
  replayedAt: string | null;
}
