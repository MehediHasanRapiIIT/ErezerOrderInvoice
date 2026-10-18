import { PaymentMethod } from '../../../shared/payment-methods';

export interface ExpenseCategory {
  id: number;
  name: string;
  active: boolean;
  expenseCount: number;
}

export interface Receipt {
  fileId: number;
  url: string;
  contentType: string;
  fileName?: string;
}

export interface Expense {
  id: number;
  expenseDate: string;
  category: { id: number; name: string };
  description: string;
  amount: number;
  paymentMethod: PaymentMethod;
  reference?: string;
  notes?: string;
  receipt?: Receipt;
  createdBy?: { id: number; name: string };
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface CategoryTotal {
  categoryId: number;
  name: string;
  total: number;
  count: number;
}

export interface ExpensePage {
  content: Expense[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  totalAmount: number;
  byCategory: CategoryTotal[];
}

export interface ExpenseQuery {
  q?: string;
  categoryId?: number;
  paymentMethod?: PaymentMethod;
  from?: string;
  to?: string;
  minAmount?: number;
  maxAmount?: number;
  page: number;
  size: number;
}

export interface ExpenseRequest {
  expenseDate: string;
  categoryId: number;
  description: string;
  amount: number;
  paymentMethod: PaymentMethod;
  reference: string | null;
  notes: string | null;
  receiptFileId: number | null;
  version?: number;
}

export interface UploadedReceipt {
  id: number;
  url: string;
  contentType: string;
  sizeBytes: number;
}
