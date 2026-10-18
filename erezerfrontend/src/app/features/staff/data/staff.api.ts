import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { Role } from '../../../core/auth/auth.models';
import { Page } from '../../../core/http/page';
import { CreateStaffRequest, PermissionInfo, StaffMember, UpdateStaffRequest } from './staff.models';

@Injectable({ providedIn: 'root' })
export class StaffApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/staff`;

  list(query: { q?: string; role?: Role; active?: boolean; page: number; size: number }): Observable<Page<StaffMember>> {
    let params = new HttpParams().set('page', query.page).set('size', query.size);
    if (query.q) params = params.set('q', query.q);
    if (query.role) params = params.set('role', query.role);
    if (query.active !== undefined) params = params.set('active', query.active);
    return this.http.get<Page<StaffMember>>(this.baseUrl, { params });
  }

  get(id: number): Observable<StaffMember> {
    return this.http.get<StaffMember>(`${this.baseUrl}/${id}`);
  }

  create(request: CreateStaffRequest): Observable<StaffMember> {
    return this.http.post<StaffMember>(this.baseUrl, request);
  }

  update(id: number, request: UpdateStaffRequest): Observable<StaffMember> {
    return this.http.put<StaffMember>(`${this.baseUrl}/${id}`, request);
  }

  setActive(id: number, active: boolean): Observable<StaffMember> {
    return this.http.post<StaffMember>(`${this.baseUrl}/${id}/${active ? 'activate' : 'deactivate'}`, null);
  }

  resetPassword(id: number, newPassword: string): Observable<StaffMember> {
    return this.http.post<StaffMember>(`${this.baseUrl}/${id}/reset-password`, { newPassword });
  }

  unlock(id: number): Observable<StaffMember> {
    return this.http.post<StaffMember>(`${this.baseUrl}/${id}/unlock`, null);
  }

  permissionCatalog(): Observable<PermissionInfo[]> {
    return this.http.get<PermissionInfo[]>(`${environment.apiBaseUrl}/permissions`);
  }
}
