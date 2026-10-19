import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Page } from '../../core/http/page';

/** Mirrors com.erezer.admin.stock.dto.StockDtos. */

/** One size of a variant. `legacy` = "No size": pieces counted before sizes existed. */
export interface VariantSize {
  stockItemId: number;
  sizeId: number;
  size: string;
  legacy: boolean;
  quantity: number;
  low: boolean;
}

export interface Variant {
  id: number;
  name: string;
  quantity: number;
  low: boolean;
  sizes: VariantSize[];
  updatedAt: string;
  version: number;
}

export interface VariantSuggestion {
  id: number;
  name: string;
  quantity: number;
  sizes: VariantSize[];
}

export interface Size {
  id: number;
  name: string;
  position: number;
  active: boolean;
  /** Variants carrying the size. */
  variants: number;
  /** Pieces in stock in this size. */
  units: number;
}

export interface StockMovement {
  id: number;
  change: number;
  quantityAfter: number;
  size?: string;
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
  /** Variant + size combinations running low. */
  lowStock: number;
  lowStockThreshold: number;
}

export type StockMode = 'SET' | 'ADD' | 'REMOVE';

export interface SizeQuantity {
  sizeId: number;
  quantity: number;
}

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

  /** The sizes of one variant (for the order form's size field). */
  sizesOf(variantId: number): Observable<VariantSize[]> {
    return this.http.get<VariantSize[]>(`${this.base}/variants/${variantId}/sizes`);
  }

  summary(): Observable<StockSummary> {
    return this.http.get<StockSummary>(`${this.base}/summary`);
  }

  create(name: string, sizes: SizeQuantity[]): Observable<Variant> {
    return this.http.post<Variant>(`${this.base}/variants`, { name, sizes });
  }

  createAll(items: { name: string; sizes: SizeQuantity[] }[]): Observable<Variant[]> {
    return this.http.post<Variant[]>(`${this.base}/variants/bulk`, { items });
  }

  update(id: number, body: { name: string; sizes: SizeQuantity[]; note: string | null; version: number }): Observable<Variant> {
    return this.http.put<Variant>(`${this.base}/variants/${id}`, body);
  }

  updateAll(items: { stockItemId: number; mode: StockMode; amount: number }[], note: string | null): Observable<Variant[]> {
    return this.http.post<Variant[]>(`${this.base}/variants/bulk-update`, { items, note });
  }

  merge(id: number, targetVariantId: number, sizeId: number): Observable<Variant> {
    return this.http.post<Variant>(`${this.base}/variants/${id}/merge`, { targetVariantId, sizeId });
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/variants/${id}`);
  }

  history(id: number, page: number): Observable<Page<StockMovement>> {
    return this.http.get<Page<StockMovement>>(`${this.base}/variants/${id}/history`, { params: { page, size: 20 } });
  }

  // ---- Sizes (changing them is admin-only) ----

  sizes(): Observable<Size[]> {
    return this.http.get<Size[]>(`${this.base}/sizes`);
  }

  createSize(name: string): Observable<Size[]> {
    return this.http.post<Size[]>(`${this.base}/sizes`, { name });
  }

  updateSize(id: number, name: string, active: boolean): Observable<Size[]> {
    return this.http.put<Size[]>(`${this.base}/sizes/${id}`, { name, active });
  }

  reorderSizes(ids: number[]): Observable<Size[]> {
    return this.http.put<Size[]>(`${this.base}/sizes/order`, { ids });
  }

  deleteSize(id: number): Observable<Size[]> {
    return this.http.delete<Size[]>(`${this.base}/sizes/${id}`);
  }
}
