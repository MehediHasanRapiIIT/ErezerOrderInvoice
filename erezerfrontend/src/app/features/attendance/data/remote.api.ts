import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { Page } from '../../../core/http/page';
import { UploadedFile } from '../../assets/data/asset.models';
import { RemoteDay, RemoteMe, RemoteMonth, RemotePerson, RemoteSummary, SessionRequest } from './remote.models';

@Injectable({ providedIn: 'root' })
export class RemoteApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/remote`;

  /** Whether the signed-in person works remotely. */
  myMode(): Observable<{ remote: boolean }> {
    return this.http.get<{ remote: boolean }>(`${environment.apiBaseUrl}/attendance/my-mode`);
  }

  setMode(userId: number, remote: boolean): Observable<void> {
    return this.http.put<void>(`${environment.apiBaseUrl}/attendance/mode/${userId}`, { remote });
  }

  me(): Observable<RemoteMe> {
    return this.http.get<RemoteMe>(`${this.baseUrl}/me`);
  }

  myMonth(month: string): Observable<RemoteMonth> {
    return this.http.get<RemoteMonth>(`${this.baseUrl}/me/month`, { params: { month } });
  }

  uploadImage(file: File): Observable<UploadedFile> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.http.post<UploadedFile>(`${environment.apiBaseUrl}/files/remote-images`, form);
  }

  on(body: { imageFileId: number | null; note: string | null; reason: string | null }): Observable<RemoteMe> {
    return this.http.post<RemoteMe>(`${this.baseUrl}/on`, body);
  }

  off(body: { done: boolean; note: string | null; reason: string | null }): Observable<RemoteMe> {
    return this.http.post<RemoteMe>(`${this.baseUrl}/off`, body);
  }

  summary(month: string, userId: number | null): Observable<RemoteSummary> {
    return this.http.get<RemoteSummary>(`${this.baseUrl}/summary`, { params: withUser(new HttpParams().set('month', month), userId) });
  }

  people(month: string, page: number, size: number): Observable<Page<RemotePerson>> {
    return this.http.get<Page<RemotePerson>>(`${this.baseUrl}/people`, { params: { month, page, size } });
  }

  /** Everyone on a date, or one person's month. */
  days(date: string, month: string, userId: number | null, page: number, size: number): Observable<Page<RemoteDay>> {
    const params = withUser(new HttpParams().set('date', date).set('month', month).set('page', page).set('size', size), userId);
    return this.http.get<Page<RemoteDay>>(`${this.baseUrl}/days`, { params });
  }

  pending(userId: number | null, page: number, size: number): Observable<Page<RemoteDay>> {
    return this.http.get<Page<RemoteDay>>(`${this.baseUrl}/pending`, { params: withUser(new HttpParams().set('page', page).set('size', size), userId) });
  }

  review(dayId: number, lateFee: number, note: string | null): Observable<RemoteDay> {
    return this.http.post<RemoteDay>(`${this.baseUrl}/days/${dayId}/review`, { lateFee, note });
  }

  deleteDay(dayId: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/days/${dayId}`);
  }

  addSession(body: SessionRequest): Observable<RemoteDay> {
    return this.http.post<RemoteDay>(`${this.baseUrl}/sessions`, body);
  }

  updateSession(id: number, body: SessionRequest): Observable<RemoteDay> {
    return this.http.put<RemoteDay>(`${this.baseUrl}/sessions/${id}`, body);
  }

  deleteSession(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/sessions/${id}`);
  }

  exportMonth(month: string): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.baseUrl}/month/export.xlsx`, { params: { month }, responseType: 'blob', observe: 'response' });
  }
}

function withUser(params: HttpParams, userId: number | null): HttpParams {
  return userId === null ? params : params.set('userId', userId);
}
