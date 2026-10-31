import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { Page } from '../../../core/http/page';
import { UploadedFile } from '../../assets/data/asset.models';
import {
  AttendanceRecord,
  AttendanceSummary,
  DayRow,
  EmployeeOption,
  MonthRow,
  MonthSheet,
  MyToday,
  Position,
  Shift,
  SpecialHours,
  SpecialHoursRequest,
  WeekDay,
  WorkLocation,
} from './attendance.models';

export interface LocationRequest {
  name: string;
  latitude: number | null;
  longitude: number | null;
  radiusM: number;
  active: boolean;
}

@Injectable({ providedIn: 'root' })
export class AttendanceApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/attendance`;

  today(): Observable<MyToday> {
    return this.http.get<MyToday>(`${this.baseUrl}/me`);
  }

  myMonth(month: string): Observable<MonthRow> {
    return this.http.get<MonthRow>(`${this.baseUrl}/me/month`, { params: { month } });
  }

  /** The photo goes to storage first; check-in then refers to it by id. */
  uploadSelfie(photo: Blob): Observable<UploadedFile> {
    const form = new FormData();
    form.append('file', photo, 'selfie.jpg');
    return this.http.post<UploadedFile>(`${environment.apiBaseUrl}/files/selfies`, form);
  }

  checkIn(position: Position | null, photoFileId: number, reason: string | null): Observable<AttendanceRecord> {
    return this.http.post<AttendanceRecord>(`${this.baseUrl}/check-in`, { ...position, photoFileId, reason });
  }

  checkOut(position: Position | null, reason: string | null): Observable<AttendanceRecord> {
    return this.http.post<AttendanceRecord>(`${this.baseUrl}/check-out`, { ...position, reason });
  }

  employees(): Observable<EmployeeOption[]> {
    return this.http.get<EmployeeOption[]>(`${this.baseUrl}/employees`);
  }

  summary(month: string, userId: number | null): Observable<AttendanceSummary> {
    return this.http.get<AttendanceSummary>(`${this.baseUrl}/summary`, { params: withUser(new HttpParams().set('month', month), userId) });
  }

  day(date: string, page: number, size: number): Observable<Page<DayRow>> {
    return this.http.get<Page<DayRow>>(`${this.baseUrl}/day`, { params: { date, page, size } });
  }

  employeeDays(userId: number, month: string, page: number, size: number): Observable<Page<DayRow>> {
    return this.http.get<Page<DayRow>>(`${this.baseUrl}/employees/${userId}/days`, { params: { month, page, size } });
  }

  month(month: string, userId: number | null, page: number, size: number): Observable<MonthSheet> {
    const params = withUser(new HttpParams().set('month', month).set('page', page).set('size', size), userId);
    return this.http.get<MonthSheet>(`${this.baseUrl}/month`, { params });
  }

  exportMonth(month: string, userId: number | null): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.baseUrl}/month/export.xlsx`, {
      params: withUser(new HttpParams().set('month', month), userId),
      responseType: 'blob',
      observe: 'response',
    });
  }

  pending(userId: number | null, page: number, size: number): Observable<Page<AttendanceRecord>> {
    const params = withUser(new HttpParams().set('page', page).set('size', size), userId);
    return this.http.get<Page<AttendanceRecord>>(`${this.baseUrl}/pending`, { params });
  }

  review(id: number, lateFee: number, note: string | null): Observable<AttendanceRecord> {
    return this.http.post<AttendanceRecord>(`${this.baseUrl}/records/${id}/review`, { lateFee, note });
  }

  correct(body: { userId: number; date: string; checkIn: string | null; checkOut: string | null; note: string }): Observable<void> {
    return this.http.put<void>(`${this.baseUrl}/day`, body);
  }

  shifts(): Observable<Shift[]> {
    return this.http.get<Shift[]>(`${this.baseUrl}/shifts`);
  }

  setShift(userId: number, body: { checkIn: string; checkOut: string; graceMinutes: number; weeklyOff: WeekDay[] }): Observable<void> {
    return this.http.put<void>(`${this.baseUrl}/shifts/${userId}`, body);
  }

  removeShift(userId: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/shifts/${userId}`);
  }

  specialHours(): Observable<SpecialHours[]> {
    return this.http.get<SpecialHours[]>(`${this.baseUrl}/special-hours`);
  }

  addSpecialHours(body: SpecialHoursRequest): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/special-hours`, body);
  }

  updateSpecialHours(id: number, body: SpecialHoursRequest): Observable<void> {
    return this.http.put<void>(`${this.baseUrl}/special-hours/${id}`, body);
  }

  deleteSpecialHours(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/special-hours/${id}`);
  }

  locations(): Observable<WorkLocation[]> {
    return this.http.get<WorkLocation[]>(`${this.baseUrl}/locations`);
  }

  createLocation(body: LocationRequest): Observable<WorkLocation[]> {
    return this.http.post<WorkLocation[]>(`${this.baseUrl}/locations`, body);
  }

  updateLocation(id: number, body: LocationRequest): Observable<WorkLocation[]> {
    return this.http.put<WorkLocation[]>(`${this.baseUrl}/locations/${id}`, body);
  }

  deleteLocation(id: number): Observable<WorkLocation[]> {
    return this.http.delete<WorkLocation[]>(`${this.baseUrl}/locations/${id}`);
  }
}

function withUser(params: HttpParams, userId: number | null): HttpParams {
  return userId === null ? params : params.set('userId', userId);
}
