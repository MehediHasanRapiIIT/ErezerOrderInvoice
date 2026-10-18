/** Mirrors com.erezer.admin.order.PaymentMethod; used by orders and expenses. */
export type PaymentMethod = 'CASH' | 'BKASH' | 'NAGAD' | 'BANK_TRANSFER' | 'CARD';

export const PAYMENT_LABELS: Record<PaymentMethod, string> = {
  CASH: 'Cash',
  BKASH: 'bKash',
  NAGAD: 'Nagad',
  BANK_TRANSFER: 'Bank transfer',
  CARD: 'Card',
};

export const PAYMENT_METHODS = Object.keys(PAYMENT_LABELS) as PaymentMethod[];
