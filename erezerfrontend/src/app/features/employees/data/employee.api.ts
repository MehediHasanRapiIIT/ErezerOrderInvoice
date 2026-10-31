import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { Page } from '../../../core/http/page';
import { UploadedFile } from '../../assets/data/asset.models';
import { Employee, EmployeeRequest, EmployeeSummary } from './employee.models';

@Injectable({ providedIn: 'root' })
export class EmployeeApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/employees`;

  list(q: string, page: number, size: number): Observable<Page<EmployeeSummary>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (q) params = params.set('q', q);
    return this.http.get<Page<EmployeeSummary>>(this.baseUrl, { params });
  }

  get(id: number): Observable<Employee> {
    return this.http.get<Employee>(`${this.baseUrl}/${id}`);
  }

  create(request: EmployeeRequest): Observable<Employee> {
    return this.http.post<Employee>(this.baseUrl, request);
  }

  update(id: number, request: EmployeeRequest): Observable<Employee> {
    return this.http.put<Employee>(`${this.baseUrl}/${id}`, request);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  /** Photo, NID or ID card image, uploaded before the form is saved. */
  uploadImage(file: File): Observable<UploadedFile> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<UploadedFile>(`${environment.apiBaseUrl}/files/employee-images`, form);
  }
}
