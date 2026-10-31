import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { Page } from '../../../core/http/page';

/** Empty fields are left out of the API's answers, hence the optional ones. */
export interface NotificationItem {
  id: number;
  action: string;
  section: string;
  title: string;
  body?: string;
  link?: string;
  actorName: string;
  actorRole: string;
  createdAt: string;
  read: boolean;
}

export interface ActionInfo {
  action: string;
  section: string;
  label: string;
}

export interface Rule {
  action: string;
  section: string;
  label: string;
  enabled: boolean;
  push: boolean;
  fromModerator: boolean;
  fromEmployee: boolean;
  fromAdmin: boolean;
  recipients: number;
}

export interface NotifyPerson {
  id: number;
  fullName: string;
  role: string;
  actions: number;
  devices: number;
}

export interface Subscription {
  action: string;
  see: boolean;
  push: boolean;
}

export interface FirebaseSettings {
  configured: boolean;
  projectId?: string;
  clientEmail?: string;
  apiKey?: string;
  appId?: string;
  messagingSenderId?: string;
  vapidKey?: string;
}

export interface PushConfig {
  configured: boolean;
  firebase: Record<string, string>;
  vapidKey?: string;
}

export interface NotificationFilter {
  unread: boolean;
  section: string;
  actorId: number | null;
  from: string;
  to: string;
}

@Injectable({ providedIn: 'root' })
export class NotificationApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/notifications`;

  list(f: NotificationFilter, page: number, size: number): Observable<Page<NotificationItem>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (f.unread) params = params.set('unread', true);
    if (f.section) params = params.set('section', f.section);
    if (f.actorId !== null) params = params.set('actorId', f.actorId);
    if (f.from) params = params.set('from', f.from);
    if (f.to) params = params.set('to', f.to);
    return this.http.get<Page<NotificationItem>>(this.baseUrl, { params });
  }

  unread(): Observable<{ count: number }> {
    return this.http.get<{ count: number }>(`${this.baseUrl}/unread-count`);
  }

  actions(): Observable<ActionInfo[]> {
    return this.http.get<ActionInfo[]>(`${this.baseUrl}/actions`);
  }

  markRead(id: number): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/${id}/read`, null);
  }

  markAllRead(): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/read-all`, null);
  }

  pushConfig(): Observable<PushConfig> {
    return this.http.get<PushConfig>(`${this.baseUrl}/push-config`);
  }

  addDevice(token: string): Observable<void> {
    return this.http.put<void>(`${this.baseUrl}/devices`, { token });
  }

  removeDevice(token: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/devices/remove`, { token });
  }

  rules(): Observable<Rule[]> {
    return this.http.get<Rule[]>(`${this.baseUrl}/rules`);
  }

  saveRules(rules: Rule[]): Observable<void> {
    return this.http.put<void>(`${this.baseUrl}/rules`, rules.map(({ action, enabled, push, fromModerator, fromEmployee, fromAdmin }) => ({
      action, enabled, push, fromModerator, fromEmployee, fromAdmin,
    })));
  }

  people(): Observable<NotifyPerson[]> {
    return this.http.get<NotifyPerson[]>(`${this.baseUrl}/people`);
  }

  subscriptions(userId: number): Observable<Subscription[]> {
    return this.http.get<Subscription[]>(`${this.baseUrl}/people/${userId}`);
  }

  saveSubscriptions(userId: number, subscriptions: Subscription[]): Observable<void> {
    return this.http.put<void>(`${this.baseUrl}/people/${userId}`, subscriptions);
  }

  settings(): Observable<FirebaseSettings> {
    return this.http.get<FirebaseSettings>(`${this.baseUrl}/settings`);
  }

  saveSettings(body: { serviceAccount: string; webConfig: string; vapidKey: string }): Observable<FirebaseSettings> {
    return this.http.put<FirebaseSettings>(`${this.baseUrl}/settings`, body);
  }

  test(): Observable<{ sent: number; failed: number }> {
    return this.http.post<{ sent: number; failed: number }>(`${this.baseUrl}/settings/test`, null);
  }
}
