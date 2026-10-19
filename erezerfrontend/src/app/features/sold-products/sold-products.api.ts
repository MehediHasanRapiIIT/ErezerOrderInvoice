import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, shareReplay } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Page } from '../../core/http/page';

/** Mirrors com.erezer.admin.sales.SoldProductService. */
export interface SoldRow {
  orderId: number;
  orderNumber: string;
  deliveredAt: string;
  categoryKey: string;
  category: string;
  pieces: number;
  sale: number;
  deliveryCharge: number;
  courierCost: number;
}

export interface CategoryTotal {
  categoryKey: string | null;
  category: string;
  orders: number;
  pieces: number;
  sale: number;
  deliveryCharge: number;
  courierCost: number;
}

export interface SoldSummary {
  from: string;
  to: string;
  categories: CategoryTotal[];
  totals: CategoryTotal;
}

export interface SoldQuery {
  from: string;
  to: string;
  category?: string;
  q?: string;
}

@Injectable({ providedIn: 'root' })
export class SoldProductsApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/sold-products`;
  private names$?: Observable<string[]>;

  rows(query: SoldQuery, page: number, size: number): Observable<Page<SoldRow>> {
    return this.http.get<Page<SoldRow>>(this.base, { params: params(query).set('page', page).set('size', size) });
  }

  summary(query: SoldQuery): Observable<SoldSummary> {
    return this.http.get<SoldSummary>(`${this.base}/summary`, { params: params(query) });
  }

  exportExcel(query: SoldQuery): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.base}/export.xlsx`, { params: params(query), responseType: 'blob', observe: 'response' });
  }

  /** Product names already used on orders (cached for the session), for the Add Order suggestions. */
  productNames(): Observable<string[]> {
    this.names$ ??= this.http.get<string[]>(`${this.base}/product-names`).pipe(shareReplay(1));
    return this.names$;
  }

  /** Forget the cached names after an order was saved, so new names appear next time. */
  refreshNames(): void {
    this.names$ = undefined;
  }
}

function params(query: SoldQuery): HttpParams {
  let p = new HttpParams().set('from', query.from).set('to', query.to);
  if (query.category) p = p.set('category', query.category);
  if (query.q) p = p.set('q', query.q);
  return p;
}
