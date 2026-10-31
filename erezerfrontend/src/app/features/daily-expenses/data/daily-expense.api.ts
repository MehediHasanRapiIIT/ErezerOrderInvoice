import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { Page } from '../../../core/http/page';
import { UploadedFile } from '../../assets/data/asset.models';
import { CreateDailyExpense, DailyCategory, DailyExpense, DailyFilter, DailySummary, Person } from './daily-expense.models';

@Injectable({ providedIn: 'root' })
export class DailyExpenseApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/daily-expenses`;
  private readonly categoriesUrl = `${environment.apiBaseUrl}/daily-expense-categories`;

  list(filter: DailyFilter, page: number, size: number): Observable<Page<DailyExpense>> {
    let params = filterParams(filter).set('page', page).set('size', size);
    if (filter.status) params = params.set('status', filter.status);
    return this.http.get<Page<DailyExpense>>(this.baseUrl, { params });
  }

  summary(filter: DailyFilter): Observable<DailySummary> {
    return this.http.get<DailySummary>(`${this.baseUrl}/summary`, { params: filterParams(filter) });
  }

  people(): Observable<Person[]> {
    return this.http.get<Person[]>(`${this.baseUrl}/people`);
  }

  create(request: CreateDailyExpense): Observable<DailyExpense> {
    return this.http.post<DailyExpense>(this.baseUrl, request);
  }

  update(id: number, request: CreateDailyExpense): Observable<DailyExpense> {
    return this.http.put<DailyExpense>(`${this.baseUrl}/${id}`, request);
  }

  /** Takes back one's own, or with the delete permission removes anyone's. */
  cancel(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  approve(id: number, paid: number): Observable<DailyExpense> {
    return this.http.post<DailyExpense>(`${this.baseUrl}/${id}/approve`, { paid });
  }

  reject(id: number, reason: string): Observable<DailyExpense> {
    return this.http.post<DailyExpense>(`${this.baseUrl}/${id}/reject`, { reason });
  }

  updatePaid(id: number, paid: number): Observable<DailyExpense> {
    return this.http.post<DailyExpense>(`${this.baseUrl}/${id}/paid`, { paid });
  }

  /** The bill, uploaded before the form is saved. */
  uploadReceipt(file: File): Observable<UploadedFile> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<UploadedFile>(`${environment.apiBaseUrl}/files/daily-expense-receipts`, form);
  }

  categories(): Observable<DailyCategory[]> {
    return this.http.get<DailyCategory[]>(this.categoriesUrl);
  }

  createCategory(name: string): Observable<void> {
    return this.http.post<void>(this.categoriesUrl, { name });
  }

  updateCategory(id: number, name: string, active: boolean): Observable<void> {
    return this.http.put<void>(`${this.categoriesUrl}/${id}`, { name, active });
  }

  deleteCategory(id: number): Observable<void> {
    return this.http.delete<void>(`${this.categoriesUrl}/${id}`);
  }
}

function filterParams(f: DailyFilter): HttpParams {
  let params = new HttpParams();
  if (f.categoryId !== null) params = params.set('categoryId', f.categoryId);
  if (f.userId !== null) params = params.set('userId', f.userId);
  if (f.from) params = params.set('from', f.from);
  if (f.to) params = params.set('to', f.to);
  if (f.q) params = params.set('q', f.q);
  return params;
}
