import { PaymentMethod } from '../../../shared/payment-methods';

export type OrderStatus =
  | 'PLACED'
  | 'ACCEPTED'
  | 'IN_PRODUCTION'
  | 'PROCESSING'
  | 'SHIPPED'
  | 'OUT_FOR_DELIVERY'
  | 'DELIVERED'
  | 'CANCELLED'
  | 'RETURNED';

export type { PaymentMethod };

export type OrderTab = 'active' | 'history';

export type OrderType = 'SALE' | 'EXCHANGE';
export type DeliveryPaidBy = 'CUSTOMER' | 'EREZER';
export type ExchangeReason = 'WRONG_SIZE' | 'DEFECTIVE' | 'WRONG_ITEM_SENT' | 'CHANGED_MIND' | 'OTHER';
export type ReturnReason = 'REFUSED' | 'DID_NOT_LIKE' | 'WRONG_SIZE' | 'DEFECTIVE' | 'WRONG_ITEM_SENT' | 'DAMAGED_IN_DELIVERY' | 'OTHER';
export type ReturnKind = 'REFUSED' | 'AFTER_DELIVERY';

export interface OrderSummary {
  id: number;
  orderNumber: string;
  orderType: OrderType;
  customerName: string;
  customerPhone: string;
  total: number;
  /** Paid before delivery. */
  advance: number;
  /** Still to collect: total − advance. */
  due: number;
  paymentMethod: PaymentMethod;
  status: OrderStatus;
  createdAt: string;
}

export interface OrderStats {
  total: number;
  pending: number;
  inTransit: number;
  delivered: number;
}

export interface UserRef {
  id: number;
  name: string;
}

export interface OrderImage {
  fileId: number;
  url: string;
}

export interface OrderItem {
  id: number;
  productName: string;
  productCode?: string | null;
  description?: string;
  color?: string;
  size?: string;
  unitPrice: number;
  quantity: number;
  lineTotal: number;
  images: OrderImage[];
  /** Stock variant; its name is shown in `color`. */
  variantId?: number | null;
}

export interface StatusChange {
  fromStatus?: OrderStatus;
  toStatus: OrderStatus;
  note?: string;
  changedBy?: UserRef;
  changedAt: string;
}

export interface OrderNote {
  id: number;
  body: string;
  author?: UserRef;
  createdAt: string;
}

export interface OrderDetail {
  id: number;
  orderNumber: string;
  status: OrderStatus;
  paymentMethod: PaymentMethod;
  customer: { name: string; phone: string; email?: string; address: string };
  items: OrderItem[];
  subtotal: number;
  discount: number;
  deliveryCharge: number;
  total: number;
  advance: number;
  due: number;
  returnDeliveryCharge: number;
  courier?: string;
  trackingNumber?: string;
  createdBy?: UserRef;
  createdAt: string;
  updatedAt: string;
  version: number;
  editable: boolean;
  allowedNextStatuses: OrderStatus[];
  history: StatusChange[];
  notes: OrderNote[];
  orderType: OrderType;
  /** Present on exchange orders. */
  exchange?: ExchangeInfo;
  /** Exchanges made for this order. */
  exchanges: ExchangeRef[];
  /** Internal: what the courier is paid. Never on the invoice. */
  courierCost: CourierCost;
  /** The viewer may move this order to any status (allowedNextStatuses then lists every other status). */
  freeStatusChange: boolean;
  /** Returns of this order, newest first (cancelled ones marked inactive). */
  returns: ReturnView[];
  /** Delivered, with some products sent back. */
  partlyReturned: boolean;
}

export interface ReturnedLine {
  productName: string;
  productCode?: string;
  color?: string;
  size?: string;
  quantity: number;
  unitCredit: number;
  credit: number;
}

export interface ReturnView {
  id: number;
  kind: ReturnKind;
  reason: ReturnReason;
  reasonLabel: string;
  returnCharge: number;
  paidBy: DeliveryPaidBy;
  itemsValue: number;
  refundAmount: number;
  restock: boolean;
  note?: string;
  createdBy?: UserRef;
  createdAt: string;
  active: boolean;
  items: ReturnedLine[];
}

export interface ReturnableItem {
  id: number;
  productName: string;
  productCode?: string;
  color?: string;
  size?: string;
  unitPrice: number;
  unitCredit: number;
  quantity: number;
  alreadyReturned: number;
  alreadyExchanged: number;
  remaining: number;
  images: OrderImage[];
}

export interface ReturnOptions {
  orderId: number;
  orderNumber: string;
  customer: { name: string; phone: string; email?: string; address: string };
  deliveredAt: string;
  deliveryCharge: number;
  items: ReturnableItem[];
  returns: ReturnView[];
}

export interface ReturnRequest {
  items: { orderItemId: number; quantity: number }[];
  reason: ReturnReason;
  paidBy: DeliveryPaidBy;
  returnCharge: number;
  restock: boolean;
  note: string | null;
}

export interface CourierCost {
  /** False until the parcel is shipped. */
  applicable: boolean;
  /** The courier's real charge when known (Steadfast or staff); null = unknown. */
  courierCharge: number | null;
  returnDeliveryCharge: number;
  total: number | null;
  /** True when the delivery charge stands in for the unknown real charge. */
  estimated: boolean;
  /** Returned without being delivered: the charge covers both ways. */
  refused: boolean;
}

export interface ExchangeInfo {
  ofOrderId: number;
  ofOrderNumber: string;
  reason: ExchangeReason;
  reasonLabel: string;
  deliveryPaidBy: DeliveryPaidBy;
  credit: number;
  priceDifference: number;
  refund: boolean;
  refundAmount: number;
  returnedItems: { productName: string; productCode: string; color?: string; size?: string; quantity: number; unitCredit: number; credit: number }[];
}

export interface ExchangeRef {
  id: number;
  orderNumber: string;
  status: OrderStatus;
  createdAt: string;
}

export interface ExchangeCandidate {
  id: number;
  orderNumber: string;
  customerName: string;
  customerPhone: string;
  total: number;
  deliveredAt: string;
}

export interface ExchangeableItem {
  id: number;
  productName: string;
  productCode?: string | null;
  description?: string;
  color?: string;
  size?: string;
  unitPrice: number;
  unitCredit: number;
  quantity: number;
  alreadyExchanged: number;
  remaining: number;
  images: OrderImage[];
  variantId?: number | null;
}

export interface ExchangeOptions {
  orderId: number;
  orderNumber: string;
  customer: { name: string; phone: string; email?: string; address: string };
  deliveredAt: string;
  deadline: string;
  withinTimeLimit: boolean;
  canOverrideTimeLimit: boolean;
  timeLimitDays: number;
  defaultDeliveryCharge: number;
  items: ExchangeableItem[];
  exchanges: ExchangeRef[];
}

export interface ExchangeRequest {
  items: { originalItemId: number; quantity: number; replacement: OrderItemRequest }[];
  reason: ExchangeReason;
  deliveryPaidBy: DeliveryPaidBy;
  deliveryCharge: number;
  refund: boolean;
  overrideTimeLimit: boolean;
  note: string | null;
}

export interface OrderItemRequest {
  productName: string;
  productCode: string | null;
  description: string | null;
  color: string | null;
  size: string | null;
  unitPrice: number;
  quantity: number;
  imageIds: number[];
  /** Stock variant picked from the list (its name goes in `color`). */
  variantId?: number | null;
}

export interface OrderRequest {
  orderNumber: string | null;
  paymentMethod: PaymentMethod;
  customer: { name: string; phone: string; email: string | null; address: string };
  deliveryCharge: number;
  discount: number;
  items: OrderItemRequest[];
  version?: number;
  /** Optional: paid before delivery. */
  advance: number;
}

export interface StatusChangeRequest {
  status: OrderStatus;
  courier?: string | null;
  trackingNumber?: string | null;
  note?: string | null;
  returnDeliveryCharge?: number | null;
  /** When returning: who pays the return charge. */
  returnPaidBy?: DeliveryPaidBy | null;
  returnReason?: ReturnReason | null;
}

export interface OrderQuery {
  tab: OrderTab;
  q?: string;
  status?: OrderStatus;
  paymentMethod?: PaymentMethod;
  type?: OrderType;
  /** ADVANCE: an advance was paid; DUE: something is still to collect. */
  balance?: 'ADVANCE' | 'DUE';
  from?: string;
  to?: string;
  page: number;
  size: number;
}

export interface UploadedFile {
  id: number;
  url: string;
  contentType: string;
  sizeBytes: number;
}
