import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { Expense, ExpenseCategory, ExpensePage, ExpenseQuery, ExpenseRequest, UploadedReceipt } from './expense.models';

@Injectable({ providedIn: 'root' })
export class ExpenseApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/expenses`;
  private readonly categoriesUrl = `${environment.apiBaseUrl}/expense-categories`;

  list(query: ExpenseQuery): Observable<ExpensePage> {
    return this.http.get<ExpensePage>(this.baseUrl, {
      params: filterParams(query).set('page', query.page).set('size', query.size),
    });
  }

  exportExcel(query: ExpenseQuery): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.baseUrl}/export.xlsx`, {
      params: filterParams(query),
      responseType: 'blob',
      observe: 'response',
    });
  }

  get(id: number): Observable<Expense> {
    return this.http.get<Expense>(`${this.baseUrl}/${id}`);
  }

  create(request: ExpenseRequest): Observable<Expense> {
    return this.http.post<Expense>(this.baseUrl, request);
  }

  update(id: number, request: ExpenseRequest): Observable<Expense> {
    return this.http.put<Expense>(`${this.baseUrl}/${id}`, request);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  uploadReceipt(file: File): Observable<UploadedReceipt> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.http.post<UploadedReceipt>(`${environment.apiBaseUrl}/files/receipts`, form);
  }

  categories(): Observable<ExpenseCategory[]> {
    return this.http.get<ExpenseCategory[]>(this.categoriesUrl);
  }

  createCategory(name: string): Observable<ExpenseCategory> {
    return this.http.post<ExpenseCategory>(this.categoriesUrl, { name });
  }

  updateCategory(id: number, name: string, active: boolean): Observable<void> {
    return this.http.put<void>(`${this.categoriesUrl}/${id}`, { name, active });
  }

  deleteCategory(id: number): Observable<void> {
    return this.http.delete<void>(`${this.categoriesUrl}/${id}`);
  }
}

function filterParams(query: ExpenseQuery): HttpParams {
  let params = new HttpParams();
  for (const key of ['q', 'categoryId', 'paymentMethod', 'from', 'to', 'minAmount', 'maxAmount'] as const) {
    const value = query[key];
    if (value !== undefined && value !== null && value !== '') {
      params = params.set(key, value);
    }
  }
  return params;
}
