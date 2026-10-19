import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { PaymentMethod } from '../../shared/payment-methods';
import { OrderStatus } from '../orders/data/order.models';

/** Mirrors com.erezer.admin.courier.CourierDtos and CourierSettingsService. */
export interface CourierAvailability {
  enabled: boolean;
  defaultDeliveryType: number;
}

export interface CourierSettings {
  enabled: boolean;
  baseUrl: string;
  defaultDeliveryType: number;
  apiKeyHint?: string;
  secretKeySet: boolean;
  webhookTokenSet: boolean;
  webhookPath: string;
  updatedAt: string;
}

export interface CourierSettingsUpdate {
  enabled: boolean;
  baseUrl: string;
  defaultDeliveryType: number;
  apiKey: string | null;
  secretKey: string | null;
}

export interface ParcelPreview {
  orderId: number;
  orderNumber: string;
  orderStatus: OrderStatus;
  recipientName: string;
  recipientPhone: string;
  recipientAddress: string;
  paymentMethod: PaymentMethod;
  orderTotal: number;
  codAmount: number;
  defaultCodAmount: number;
  deliveryType: number;
  note?: string;
  itemDescription?: string;
  ready: boolean;
  blockedReason?: string;
  problems: string[];
  customerHistory?: { orders: number; delivered: number; returned: number };
  existingTrackingCode?: string;
}

export interface PreviewResponse {
  parcels: ParcelPreview[];
  ready: number;
  totalCod: number;
}

export interface ParcelEdits {
  codAmount?: number | null;
  deliveryType?: number | null;
  note?: string | null;
  itemDescription?: string | null;
}

export interface Shipment {
  provider: string;
  state: 'CONFIRMING' | 'BOOKED';
  invoice: string;
  consignmentId?: string;
  trackingCode?: string;
  trackingLink?: string;
  codAmount: number;
  deliveryType: number;
  note?: string;
  itemDescription?: string;
  courierStatus?: string;
  courierStatusAt?: string;
  trackingMessage?: string;
  deliveryCharge?: number;
  needsAttention: boolean;
  createdAt: string;
  events: { source: string; courierStatus?: string; receivedAt: string }[];
}

export interface SendResult {
  state: 'BOOKED' | 'CONFIRMING';
  message: string;
  shipment: Shipment;
}

export interface BatchView {
  id: number;
  status: 'RUNNING' | 'COMPLETED';
  total: number;
  succeeded: number;
  failed: number;
  processed: number;
  createdAt: string;
  finishedAt?: string;
  items: {
    orderId: number;
    orderNumber: string;
    customerName?: string;
    status: 'PENDING' | 'SUCCESS' | 'FAILED';
    message?: string;
    trackingCode?: string;
    codAmount: number;
  }[];
}

/** Steadfast delivery statuses in plain words. */
export const COURIER_STATUS_LABELS: Record<string, string> = {
  in_review: 'In review',
  pending: 'With courier (not yet attempted)',
  hold: 'On hold (contact Steadfast support)',
  delivered: 'Delivered',
  partial_delivered: 'Partially delivered',
  cancelled: 'Cancelled (returned)',
  delivered_approval_pending: 'Delivered, awaiting approval',
  partial_delivered_approval_pending: 'Partial delivery, awaiting approval',
  cancelled_approval_pending: 'Cancellation awaiting approval',
  unknown_approval_pending: 'Awaiting approval',
  exceptional: 'Lost or damaged (exceptional)',
  unknown: 'Unknown',
};

/** Steadfast accepts COD from ৳0 to ৳1,000,000 with up to 2 decimal places (paisa). */
export function isValidCod(cod: number | null | undefined): boolean {
  if (cod === null || cod === undefined || `${cod}`.trim() === '') return false;
  const n = Number(cod);
  return Number.isFinite(n) && n >= 0 && n <= 1_000_000 && Number(n.toFixed(2)) === n;
}

/** The customer's record with all Steadfast merchants. A missing ratio means too little history to say. */
export interface FraudReport {
  deliveryRatio?: number | null;
  cancellationRatio?: number | null;
  deliveredCount?: number | null;
  cancelledCount?: number | null;
  totalReports?: number | null;
  volumeBand?: string | null;
  volumeRange?: string | null;
  fraudCategories: string[];
}

export const DELIVERY_TYPES = [
  { value: 0, label: 'Home delivery' },
  { value: 1, label: 'Point / hub pickup' },
];

/** Orders that can be handed to the courier. */
export const SENDABLE_STATUSES: readonly OrderStatus[] = ['ACCEPTED', 'PROCESSING'];

@Injectable({ providedIn: 'root' })
export class CourierApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/courier`;

  availability(): Observable<CourierAvailability> {
    return this.http.get<CourierAvailability>(`${this.base}/availability`);
  }

  settings(): Observable<CourierSettings> {
    return this.http.get<CourierSettings>(`${this.base}/settings`);
  }

  saveSettings(update: CourierSettingsUpdate): Observable<CourierSettings> {
    return this.http.put<CourierSettings>(`${this.base}/settings`, update);
  }

  testConnection(): Observable<{ balance: number }> {
    return this.http.post<{ balance: number }>(`${this.base}/settings/test`, null);
  }

  regenerateWebhookToken(): Observable<{ token: string }> {
    return this.http.post<{ token: string }>(`${this.base}/settings/webhook-token`, null);
  }

  setWebhookToken(token: string): Observable<void> {
    return this.http.put<void>(`${this.base}/settings/webhook-token`, { token });
  }

  fraudCheck(phone: string): Observable<FraudReport> {
    return this.http.get<FraudReport>(`${this.base}/fraud-check`, { params: { phone } });
  }

  preview(orderIds: number[]): Observable<PreviewResponse> {
    return this.http.post<PreviewResponse>(`${this.base}/preview`, { orderIds });
  }

  send(orderId: number, edits: ParcelEdits): Observable<SendResult> {
    return this.http.post<SendResult>(`${environment.apiBaseUrl}/orders/${orderId}/courier`, edits);
  }

  shipment(orderId: number): Observable<Shipment | null> {
    return this.http.get<Shipment | null>(`${environment.apiBaseUrl}/orders/${orderId}/courier`);
  }

  refresh(orderId: number): Observable<Shipment> {
    return this.http.post<Shipment>(`${environment.apiBaseUrl}/orders/${orderId}/courier/refresh`, null);
  }

  startBatch(items: ({ orderId: number } & ParcelEdits)[]): Observable<{ batchId: number }> {
    return this.http.post<{ batchId: number }>(`${this.base}/batches`, { items });
  }

  batch(id: number): Observable<BatchView> {
    return this.http.get<BatchView>(`${this.base}/batches/${id}`);
  }
}
