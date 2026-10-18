import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

/** Mirrors com.erezer.admin.customer.CustomerDtos. */
export interface Customer {
  phoneKey: string;
  phone: string;
  name: string;
  email?: string;
  address: string;
  orders: number;
  delivered: number;
  returned: number;
  cancelled: number;
  totalPaid: number;
  firstOrderAt: string;
  lastOrderAt: string;
}

export interface CustomerPage {
  content: Customer[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  summary: { customers: number; repeatCustomers: number; customersWithReturns: number };
}

export type CustomerSegment = 'repeat' | 'returns';
export type CustomerSort = 'lastOrder' | 'totalPaid' | 'orders' | 'name';

export interface CustomerQuery {
  q?: string;
  segment?: CustomerSegment;
  sort: CustomerSort;
  page: number;
  size: number;
}

@Injectable({ providedIn: 'root' })
export class CustomerApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/customers`;

  list(query: CustomerQuery): Observable<CustomerPage> {
    return this.http.get<CustomerPage>(this.baseUrl, {
      params: params(query).set('page', query.page).set('size', query.size),
    });
  }

  /** The returning customer with this exact phone (any format), or null for a new customer. */
  lookup(phone: string): Observable<Customer | null> {
    return this.http.get<Customer | null>(`${this.baseUrl}/lookup`, { params: { phone } });
  }

  exportExcel(query: CustomerQuery): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.baseUrl}/export.xlsx`, { params: params(query), responseType: 'blob', observe: 'response' });
  }
}

function params(query: CustomerQuery): HttpParams {
  let p = new HttpParams().set('sort', query.sort);
  if (query.q) p = p.set('q', query.q);
  if (query.segment) p = p.set('segment', query.segment);
  return p;
}
