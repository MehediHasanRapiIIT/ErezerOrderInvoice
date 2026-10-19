import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { Page } from '../../../core/http/page';
import {
  DeliveryPaidBy,
  ReturnOptions,
  ReturnReason,
  ReturnRequest,
  ExchangeCandidate,
  ExchangeOptions,
  ExchangeRequest,
  OrderDetail,
  OrderQuery,
  OrderRequest,
  OrderStats,
  OrderSummary,
  StatusChangeRequest,
  UploadedFile,
} from './order.models';

@Injectable({ providedIn: 'root' })
export class OrderApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/orders`;

  list(query: OrderQuery): Observable<Page<OrderSummary>> {
    const params = filterParams(query).set('page', query.page).set('size', query.size);
    return this.http.get<Page<OrderSummary>>(this.baseUrl, { params });
  }

  /** Every order matching the list filters, as an .xlsx file. */
  exportExcel(query: OrderQuery): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.baseUrl}/export.xlsx`, {
      params: filterParams(query),
      responseType: 'blob',
      observe: 'response',
    });
  }

  invoicePdf(id: number, download = false): Observable<Blob> {
    return this.http.get(`${this.baseUrl}/${id}/invoice.pdf`, {
      params: download ? { download: true } : {},
      responseType: 'blob',
    });
  }

  /** Ids of every order matching the filters (max 500), for "select all matching". */
  ids(query: OrderQuery): Observable<{ ids: number[]; total: number }> {
    return this.http.get<{ ids: number[]; total: number }>(`${this.baseUrl}/ids`, { params: filterParams(query) });
  }

  stats(): Observable<OrderStats> {
    return this.http.get<OrderStats>(`${this.baseUrl}/stats`);
  }

  get(id: number): Observable<OrderDetail> {
    return this.http.get<OrderDetail>(`${this.baseUrl}/${id}`);
  }

  create(request: OrderRequest): Observable<{ id: number }> {
    return this.http.post<{ id: number }>(this.baseUrl, request);
  }

  update(id: number, request: OrderRequest): Observable<OrderDetail> {
    return this.http.put<OrderDetail>(`${this.baseUrl}/${id}`, request);
  }

  changeStatus(id: number, request: StatusChangeRequest): Observable<OrderDetail> {
    return this.http.post<OrderDetail>(`${this.baseUrl}/${id}/status`, request);
  }

  recordCourierCost(id: number, courierCharge: number | null, returnDeliveryCharge?: number | null): Observable<OrderDetail> {
    return this.http.put<OrderDetail>(`${this.baseUrl}/${id}/courier-cost`, { courierCharge, returnDeliveryCharge: returnDeliveryCharge ?? null });
  }

  addNote(id: number, body: string): Observable<OrderDetail> {
    return this.http.post<OrderDetail>(`${this.baseUrl}/${id}/notes`, { body });
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  /** Delivered orders by order number or customer phone. */
  lookupExchange(q: string): Observable<ExchangeCandidate[]> {
    return this.http.get<ExchangeCandidate[]>(`${environment.apiBaseUrl}/exchanges/lookup`, { params: { q } });
  }

  exchangeOptions(orderId: number): Observable<ExchangeOptions> {
    return this.http.get<ExchangeOptions>(`${this.baseUrl}/${orderId}/exchange-options`);
  }

  createExchange(orderId: number, request: ExchangeRequest): Observable<{ id: number }> {
    return this.http.post<{ id: number }>(`${this.baseUrl}/${orderId}/exchanges`, request);
  }

  returnOptions(orderId: number): Observable<ReturnOptions> {
    return this.http.get<ReturnOptions>(`${this.baseUrl}/${orderId}/return-options`);
  }

  /** Products sent back after delivery; answers with the updated order. */
  createReturn(orderId: number, request: ReturnRequest): Observable<OrderDetail> {
    return this.http.post<OrderDetail>(`${this.baseUrl}/${orderId}/returns`, request);
  }

  updateReturn(returnId: number, body: { reason: ReturnReason; paidBy: DeliveryPaidBy; returnCharge: number; note: string | null }): Observable<OrderDetail> {
    return this.http.put<OrderDetail>(`${environment.apiBaseUrl}/returns/${returnId}`, body);
  }

  uploadImages(files: File[]): Observable<UploadedFile[]> {
    const form = new FormData();
    files.forEach((file) => form.append('files', file, file.name));
    return this.http.post<UploadedFile[]>(`${environment.apiBaseUrl}/files/images`, form);
  }
}

function filterParams(query: OrderQuery): HttpParams {
  let params = new HttpParams().set('tab', query.tab);
  for (const key of ['q', 'status', 'paymentMethod', 'type', 'balance', 'from', 'to'] as const) {
    const value = query[key];
    if (value) {
      params = params.set(key, value);
    }
  }
  return params;
}
