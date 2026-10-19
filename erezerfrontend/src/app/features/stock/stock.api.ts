import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Page } from '../../core/http/page';

/** Mirrors com.erezer.admin.stock.dto.StockDtos. */
export interface Variant {
  id: number;
  name: string;
  quantity: number;
  low: boolean;
  updatedAt: string;
  version: number;
}

export interface VariantSuggestion {
  id: number;
  name: string;
  quantity: number;
}

export interface StockMovement {
  id: number;
  change: number;
  quantityAfter: number;
  reason: string;
  reasonLabel: string;
  orderId?: number;
  orderNumber?: string;
  note?: string;
  by: string;
  createdAt: string;
}

export interface StockSummary {
  variants: number;
  units: number;
  lowStock: number;
  lowStockThreshold: number;
}

export type StockMode = 'SET' | 'ADD' | 'REMOVE';

@Injectable({ providedIn: 'root' })
export class StockApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/stock`;

  list(query: { q?: string; low?: boolean; page: number; size: number; sort?: string }): Observable<Page<Variant>> {
    let params = new HttpParams().set('page', query.page).set('size', query.size).set('sort', query.sort ?? 'name,asc');
    if (query.q) params = params.set('q', query.q);
    if (query.low) params = params.set('low', true);
    return this.http.get<Page<Variant>>(`${this.base}/variants`, { params });
  }

  suggest(q: string): Observable<VariantSuggestion[]> {
    return this.http.get<VariantSuggestion[]>(`${this.base}/variants/suggest`, { params: { q } });
  }

  summary(): Observable<StockSummary> {
    return this.http.get<StockSummary>(`${this.base}/summary`);
  }

  create(name: string, quantity: number): Observable<Variant> {
    return this.http.post<Variant>(`${this.base}/variants`, { name, quantity });
  }

  createAll(items: { name: string; quantity: number }[]): Observable<Variant[]> {
    return this.http.post<Variant[]>(`${this.base}/variants/bulk`, { items });
  }

  update(id: number, body: { name: string; quantity: number; note: string | null; version: number }): Observable<Variant> {
    return this.http.put<Variant>(`${this.base}/variants/${id}`, body);
  }

  updateAll(items: { id: number; mode: StockMode; amount: number }[], note: string | null): Observable<Variant[]> {
    return this.http.post<Variant[]>(`${this.base}/variants/bulk-update`, { items, note });
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/variants/${id}`);
  }

  history(id: number, page: number): Observable<Page<StockMovement>> {
    return this.http.get<Page<StockMovement>>(`${this.base}/variants/${id}/history`, { params: { page, size: 20 } });
  }
}
