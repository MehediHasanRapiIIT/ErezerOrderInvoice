import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { DashboardSummary } from './dashboard.models';

@Injectable({ providedIn: 'root' })
export class DashboardApi {
  private readonly http = inject(HttpClient);

  summary(from: string, to: string): Observable<DashboardSummary> {
    return this.http.get<DashboardSummary>(`${environment.apiBaseUrl}/dashboard/summary`, { params: { from, to } });
  }
}
