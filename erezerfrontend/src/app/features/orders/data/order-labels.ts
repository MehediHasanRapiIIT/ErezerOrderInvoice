import { PAYMENT_LABELS as BASE_PAYMENT_LABELS, PaymentMethod } from '../../../shared/payment-methods';
import { DeliveryPaidBy, ExchangeReason, OrderPlatform, OrderStatus, OrderTab, ReturnReason } from './order.models';

export const PLATFORM_LABELS: Record<OrderPlatform, string> = {
  FACEBOOK: 'Facebook',
  INSTAGRAM: 'Instagram',
  WHATSAPP: 'WhatsApp',
  TIKTOK: 'TikTok',
  WEBSITE: 'Website',
  PHONE: 'Phone call',
  SHOP: 'In the shop',
  OTHER: 'Other',
};

export const PLATFORMS = Object.keys(PLATFORM_LABELS) as OrderPlatform[];

export const EXCHANGE_REASONS: { value: ExchangeReason; label: string; suggestedPayer: DeliveryPaidBy }[] = [
  { value: 'WRONG_SIZE', label: 'Wrong size', suggestedPayer: 'CUSTOMER' },
  { value: 'DEFECTIVE', label: 'Defective product', suggestedPayer: 'EREZER' },
  { value: 'WRONG_ITEM_SENT', label: 'Wrong item sent', suggestedPayer: 'EREZER' },
  { value: 'CHANGED_MIND', label: 'Customer changed mind', suggestedPayer: 'CUSTOMER' },
  { value: 'OTHER', label: 'Other', suggestedPayer: 'CUSTOMER' },
];

/** Why products came back: who usually pays the return charge, and whether the pieces can be resold. */
export const RETURN_REASONS: { value: ReturnReason; label: string; suggestedPayer: DeliveryPaidBy; resellable: boolean }[] = [
  { value: 'DID_NOT_LIKE', label: "Didn't like it", suggestedPayer: 'CUSTOMER', resellable: true },
  { value: 'WRONG_SIZE', label: 'Wrong size', suggestedPayer: 'CUSTOMER', resellable: true },
  { value: 'DEFECTIVE', label: 'Defective product', suggestedPayer: 'EREZER', resellable: false },
  { value: 'WRONG_ITEM_SENT', label: 'Wrong item sent', suggestedPayer: 'EREZER', resellable: true },
  { value: 'DAMAGED_IN_DELIVERY', label: 'Damaged in delivery', suggestedPayer: 'EREZER', resellable: false },
  { value: 'REFUSED', label: 'Refused at the door', suggestedPayer: 'EREZER', resellable: true },
  { value: 'OTHER', label: 'Other', suggestedPayer: 'CUSTOMER', resellable: true },
];

/** The order timeline. ('In production' is retired; it only appears in old history entries.) */
export const STATUS_FLOW: readonly OrderStatus[] = [
  'PLACED',
  'ACCEPTED',
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
