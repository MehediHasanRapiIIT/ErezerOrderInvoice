import { PAYMENT_LABELS as BASE_PAYMENT_LABELS, PaymentMethod } from '../../../shared/payment-methods';
import { OrderStatus, OrderTab } from './order.models';

export const STATUS_FLOW: readonly OrderStatus[] = [
  'PLACED',
  'ACCEPTED',
  'IN_PRODUCTION',
  'PROCESSING',
  'SHIPPED',
  'OUT_FOR_DELIVERY',
  'DELIVERED',
];

export const TAB_STATUSES: Record<OrderTab, readonly OrderStatus[]> = {
  active: STATUS_FLOW.filter((s) => s !== 'DELIVERED'),
  history: ['DELIVERED', 'CANCELLED', 'RETURNED'],
};

export const STATUS_LABELS: Record<OrderStatus, string> = {
  PLACED: 'Placed',
  ACCEPTED: 'Accepted',
  IN_PRODUCTION: 'In production',
  PROCESSING: 'Processing',
  SHIPPED: 'Shipped',
  OUT_FOR_DELIVERY: 'Out for delivery',
  DELIVERED: 'Delivered',
  CANCELLED: 'Cancelled',
  RETURNED: 'Returned',
};

/** Badge colours: text, border and translucent background. */
export const STATUS_STYLES: Record<OrderStatus, string> = {
  PLACED: 'text-amber-300 border-amber-500/40 bg-amber-500/10',
  ACCEPTED: 'text-blue-300 border-blue-500/40 bg-blue-500/10',
  IN_PRODUCTION: 'text-violet-300 border-violet-500/40 bg-violet-500/10',
  PROCESSING: 'text-orange-300 border-orange-500/40 bg-orange-500/10',
  SHIPPED: 'text-cyan-300 border-cyan-500/40 bg-cyan-500/10',
  OUT_FOR_DELIVERY: 'text-fuchsia-300 border-fuchsia-500/40 bg-fuchsia-500/10',
  DELIVERED: 'text-emerald-300 border-emerald-500/40 bg-emerald-500/10',
  CANCELLED: 'text-red-300 border-red-500/40 bg-red-500/10',
  RETURNED: 'text-rose-300 border-rose-500/40 bg-rose-500/10',
};

export { PAYMENT_METHODS } from '../../../shared/payment-methods';

/** Orders say "Cash on delivery" (as on the invoice); expenses just say "Cash". */
export const PAYMENT_LABELS: Record<PaymentMethod, string> = { ...BASE_PAYMENT_LABELS, CASH: 'Cash on delivery' };

export const SIZE_SUGGESTIONS = ['XS', 'S', 'M', 'L', 'XL', 'XXL', '3XL'];

export function isEditableStatus(status: OrderStatus): boolean {
  return status !== 'CANCELLED' && status !== 'RETURNED';
}
