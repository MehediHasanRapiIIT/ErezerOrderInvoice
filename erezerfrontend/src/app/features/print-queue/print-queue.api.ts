import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Page } from '../../core/http/page';
import { OrderPlatform, OrderStatus, OrderType } from '../orders/data/order.models';

export interface PrintImage {
  url: string;
  description?: string;
}

export interface PrintItem {
  productName: string;
  productCode?: string;
  color?: string;
  size?: string;
  quantity: number;
  description?: string;
  images: PrintImage[];
}

/** An order waiting to be shipped, with only what printing needs: no prices, no customer. */
export interface PrintOrder {
  id: number;
  orderNumber: string;
  orderType: OrderType;
  status: OrderStatus;
  orderedAt: string;
  urgent: boolean;
  platform?: OrderPlatform;
  printedAt?: string;
  printedBy?: string;
  items: PrintItem[];
}

export interface PrintCounts {
  total: number;
  printed: number;
  notPrinted: number;
}

export interface PrintQuery {
  q: string;
  /** '' both, 'true' printed only, 'false' not printed only. */
  printed: '' | 'true' | 'false';
  sort: 'oldest' | 'newest';
  page: number;
  size: number;
}

@Injectable({ providedIn: 'root' })
export class PrintQueueApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/print-queue`;

  list(query: PrintQuery): Observable<Page<PrintOrder>> {
    let params = new HttpParams().set('sort', query.sort).set('page', query.page).set('size', query.size);
    if (query.q) params = params.set('q', query.q);
    if (query.printed) params = params.set('printed', query.printed);
    return this.http.get<Page<PrintOrder>>(this.baseUrl, { params });
  }

  counts(q: string): Observable<PrintCounts> {
    return this.http.get<PrintCounts>(`${this.baseUrl}/counts`, { params: q ? { q } : {} });
  }

  markPrinted(id: number, printed: boolean): Observable<PrintOrder> {
    return this.http.put<PrintOrder>(`${this.baseUrl}/${id}/printed`, { printed });
  }
}
