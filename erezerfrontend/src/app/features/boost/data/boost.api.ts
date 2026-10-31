import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { Page } from '../../../core/http/page';

export interface BoostCost {
  id: number;
  date: string;
  usd: number;
  rate: number;
  bdt: number;
  note?: string;
  addedBy?: string;
  createdAt: string;
  version: number;
}

export interface PurchaseLine {
  usd: number;
  bdt: number;
}

export interface BoostPurchase {
  id: number;
  date: string;
  usd: number;
  bdt: number;
  rate: number;
  lines: PurchaseLine[];
  note?: string;
  addedBy?: string;
  createdAt: string;
  version: number;
}

/** The buying figures are left out for anyone who may not see purchases. */
export interface BoostSummary {
  spentUsd: number;
  spentBdt: number;
  costCount: number;
  boughtUsd?: number;
  boughtBdt?: number;
  /** All time; empty until something was bought. */
  averageRate?: number;
  /** All time: bought minus spent. */
  balanceUsd?: number;
}

export interface BoostFilter {
  from: string;
  to: string;
  q: string;
}

@Injectable({ providedIn: 'root' })
export class BoostApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/boost`;

  summary(filter: BoostFilter): Observable<BoostSummary> {
    return this.http.get<BoostSummary>(`${this.baseUrl}/summary`, { params: params(filter) });
  }

  rate(): Observable<{ rate?: number }> {
    return this.http.get<{ rate?: number }>(`${this.baseUrl}/rate`);
  }

  costs(filter: BoostFilter, page: number, size: number): Observable<Page<BoostCost>> {
    return this.http.get<Page<BoostCost>>(`${this.baseUrl}/costs`, { params: params(filter).set('page', page).set('size', size) });
  }

  addCost(body: { date: string; usd: number; rate: number; note: string | null }): Observable<BoostCost> {
    return this.http.post<BoostCost>(`${this.baseUrl}/costs`, body);
  }

  updateCost(id: number, body: { date: string; usd: number; rate: number; note: string | null; version: number }): Observable<BoostCost> {
    return this.http.put<BoostCost>(`${this.baseUrl}/costs/${id}`, body);
  }

  deleteCost(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/costs/${id}`);
  }

  purchases(filter: BoostFilter, page: number, size: number): Observable<Page<BoostPurchase>> {
    return this.http.get<Page<BoostPurchase>>(`${this.baseUrl}/purchases`, { params: params(filter).set('page', page).set('size', size) });
  }

  addPurchase(body: { date: string; lines: PurchaseLine[]; note: string | null }): Observable<BoostPurchase> {
    return this.http.post<BoostPurchase>(`${this.baseUrl}/purchases`, body);
  }

  updatePurchase(id: number, body: { date: string; lines: PurchaseLine[]; note: string | null; version: number }): Observable<BoostPurchase> {
    return this.http.put<BoostPurchase>(`${this.baseUrl}/purchases/${id}`, body);
  }

  deletePurchase(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/purchases/${id}`);
  }
}

function params(f: BoostFilter): HttpParams {
  let result = new HttpParams();
  if (f.from) result = result.set('from', f.from);
  if (f.to) result = result.set('to', f.to);
  if (f.q) result = result.set('q', f.q);
  return result;
}
