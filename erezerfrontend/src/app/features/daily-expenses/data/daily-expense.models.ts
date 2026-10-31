export type DailyExpenseStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

export const DAILY_STATUS_LABELS: Record<DailyExpenseStatus, string> = {
  PENDING: 'Waiting for approval',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
};

export const DAILY_STATUS_CLASSES: Record<DailyExpenseStatus, string> = {
  PENDING: 'border-amber-500/40 bg-amber-500/10 text-amber-300',
  APPROVED: 'border-emerald-500/40 bg-emerald-500/10 text-emerald-300',
  REJECTED: 'border-rose-500/40 bg-rose-500/10 text-rose-300',
};

/** The category filter's value for "Other". */
export const OTHER_CATEGORY = 0;

export interface Person {
  id: number;
  name: string;
}

export interface DailyCategory {
  id: number;
  name: string;
  active: boolean;
  expenseCount: number;
}

/** Empty fields are left out of the API's answers, hence the optional ones. */
export interface DailyExpense {
  id: number;
  requestedBy: Person;
  date: string;
  categoryId?: number;
  category: string;
  other: boolean;
  amount: number;
  paid: number;
  /** Only once approved. */
  due?: number;
  details?: string;
  receipt?: { url: string; contentType: string };
  status: DailyExpenseStatus;
  rejectionReason?: string;
  reviewedBy?: string;
  reviewedAt?: string;
  createdAt: string;
  version: number;
}

export interface DailySummary {
  approvedCount: number;
  approved: number;
  paid: number;
  due: number;
  pendingCount: number;
  pending: number;
}

export interface DailyFilter {
  status: DailyExpenseStatus | null;
  categoryId: number | null;
  userId: number | null;
  from: string;
  to: string;
  q: string;
}

export interface CreateDailyExpense {
  date: string;
  categoryId: number | null;
  otherCategory: string | null;
  amount: number;
  details: string | null;
  receiptFileId: number | null;
  version?: number;
}
