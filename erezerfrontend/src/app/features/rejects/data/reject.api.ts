import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { Page } from '../../../core/http/page';

export type RejectKind = 'SOLID' | 'PRINTED';

export const REJECT_KINDS: readonly RejectKind[] = ['SOLID', 'PRINTED'];

export const REJECT_KIND_LABELS: Record<RejectKind, string> = {
  SOLID: 'Solid Color',
  PRINTED: 'Printed',
};

/** Empty fields are left out of the API's answers, hence the optional ones. */
export interface Reject {
  id: number;
  date: string;
  fromStock: boolean;
  variantId?: number;
  stockItemId?: number;
  variantName: string;
  size?: string;
  kind: RejectKind;
  quantity: number;
  note?: string;
  addedBy?: string;
  createdAt: string;
  version: number;
}

export interface RejectRequest {
  date: string;
  variantId: number | null;
  stockItemId: number | null;
  otherName: string | null;
  size: string | null;
  kind: RejectKind;
  quantity: number;
  note: string | null;
  version?: number;
}

export interface RejectSummary {
  total: number;
  entries: number;
  solid: number;
  printed: number;
  fromStock: number;
  other: number;
}

export interface RejectFilter {
  from: string;
  to: string;
  q: string;
  kind: RejectKind | null;
  fromStock: boolean | null;
}

@Injectable({ providedIn: 'root' })
export class RejectApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/rejects`;

  list(f: RejectFilter, page: number, size: number): Observable<Page<Reject>> {
    let params = shared(f).set('page', page).set('size', size);
    if (f.kind) params = params.set('kind', f.kind);
    if (f.fromStock !== null) params = params.set('fromStock', f.fromStock);
    return this.http.get<Page<Reject>>(this.baseUrl, { params });
  }

  summary(f: RejectFilter): Observable<RejectSummary> {
    return this.http.get<RejectSummary>(`${this.baseUrl}/summary`, { params: shared(f) });
  }

  create(request: RejectRequest): Observable<Reject> {
    return this.http.post<Reject>(this.baseUrl, request);
  }

  update(id: number, request: RejectRequest): Observable<Reject> {
    return this.http.put<Reject>(`${this.baseUrl}/${id}`, request);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }
}

function shared(f: RejectFilter): HttpParams {
  let params = new HttpParams();
  if (f.from) params = params.set('from', f.from);
  if (f.to) params = params.set('to', f.to);
  if (f.q) params = params.set('q', f.q);
  return params;
}
