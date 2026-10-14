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

export type PaymentMethod = 'CASH' | 'BKASH' | 'NAGAD' | 'BANK_TRANSFER' | 'CARD';

export type OrderTab = 'active' | 'history';

export interface OrderSummary {
  id: number;
  orderNumber: string;
  customerName: string;
  customerPhone: string;
  total: number;
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
  productCode: string;
  description?: string;
  color?: string;
  size?: string;
  unitPrice: number;
  quantity: number;
  lineTotal: number;
  images: OrderImage[];
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
}

export interface OrderItemRequest {
  productName: string;
  productCode: string;
  description: string | null;
  color: string | null;
  size: string | null;
  unitPrice: number;
  quantity: number;
  imageIds: number[];
}

export interface OrderRequest {
  orderNumber: string | null;
  paymentMethod: PaymentMethod;
  customer: { name: string; phone: string; email: string | null; address: string };
  deliveryCharge: number;
  discount: number;
  items: OrderItemRequest[];
  version?: number;
}

export interface StatusChangeRequest {
  status: OrderStatus;
  courier?: string | null;
  trackingNumber?: string | null;
  note?: string | null;
  returnDeliveryCharge?: number | null;
}

export interface OrderQuery {
  tab: OrderTab;
  q?: string;
  status?: OrderStatus;
  paymentMethod?: PaymentMethod;
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
