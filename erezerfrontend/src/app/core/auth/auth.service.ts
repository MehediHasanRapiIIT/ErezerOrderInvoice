import { HttpClient } from '@angular/common/http';
import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { Router } from '@angular/router';
import { finalize, firstValueFrom, map, Observable, share, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  ChangePasswordRequest,
  CurrentUser,
  LoginRequest,
  Permission,
  TokenResponse,
} from './auth.models';

/** Refresh this long before the access token expires, so in-flight requests never carry a stale token. */
const REFRESH_LEEWAY_MS = 60_000;

/**
 * Owns the session. The access token lives only in memory; the refresh token is an HttpOnly cookie
 * the browser sends to /api/auth automatically, so a page reload restores the session via {@link restoreSession}.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly baseUrl = `${environment.apiBaseUrl}/auth`;

  private readonly accessToken = signal<string | null>(null);
  private readonly currentUser = signal<CurrentUser | null>(null);
  private refreshInFlight$: Observable<string> | null = null;
  private refreshTimer: ReturnType<typeof setTimeout> | undefined;

  readonly user = this.currentUser.asReadonly();
  readonly isAuthenticated = computed(() => this.currentUser() !== null);
  readonly permissions = computed(() => new Set<Permission>(this.currentUser()?.permissions ?? []));

  constructor() {
    inject(DestroyRef).onDestroy(() => clearTimeout(this.refreshTimer));
  }

  token(): string | null {
    return this.accessToken();
  }

  hasPermission(permission: Permission): boolean {
    return this.permissions().has(permission);
  }

  hasAnyPermission(permissions: readonly Permission[]): boolean {
    return permissions.length === 0 || permissions.some((p) => this.permissions().has(p));
  }

  login(request: LoginRequest): Observable<CurrentUser> {
    return this.http
      .post<TokenResponse>(`${this.baseUrl}/login`, request, { withCredentials: true })
      .pipe(
        tap((response) => this.applySession(response)),
        map((response) => response.user),
      );
  }

  /**
   * Exchanges the refresh cookie for a new access token. Concurrent callers share one request,
   * because the backend rotates the refresh token on every use.
   */
  refresh(): Observable<string> {
    if (!this.refreshInFlight$) {
      this.refreshInFlight$ = this.http
        .post<TokenResponse>(`${this.baseUrl}/refresh`, null, { withCredentials: true })
        .pipe(
          tap((response) => this.applySession(response)),
          map((response) => response.accessToken),
          finalize(() => (this.refreshInFlight$ = null)),
          share(),
        );
    }
    return this.refreshInFlight$;
  }

  /** Called once at startup. Resolves either way; an absent or expired cookie just means "signed out". */
  async restoreSession(): Promise<void> {
    try {
      await firstValueFrom(this.refresh());
    } catch {
      this.clearSession();
    }
  }

  logout(): void {
    this.http.post<void>(`${this.baseUrl}/logout`, null, { withCredentials: true }).subscribe({
      error: () => undefined, // the local session is cleared regardless
    });
    this.clearSession();
    void this.router.navigate(['/login']);
  }

  changePassword(request: ChangePasswordRequest): Observable<void> {
    return this.http
      .put<TokenResponse>(`${this.baseUrl}/password`, request, { withCredentials: true })
      .pipe(
        tap((response) => this.applySession(response)),
        map(() => undefined),
      );
  }

  /** The refresh token was rejected: drop the session and send the user to the login page. */
  expireSession(): void {
    const returnUrl = this.router.url.startsWith('/login') ? undefined : this.router.url;
    this.clearSession();
    void this.router.navigate(['/login'], { queryParams: returnUrl ? { returnUrl } : undefined });
  }

  private applySession(response: TokenResponse): void {
    this.accessToken.set(response.accessToken);
    this.currentUser.set(response.user);
    this.scheduleRefresh(new Date(response.expiresAt).getTime());
  }

  private clearSession(): void {
    clearTimeout(this.refreshTimer);
    this.accessToken.set(null);
    this.currentUser.set(null);
  }

  private scheduleRefresh(expiresAtMs: number): void {
    clearTimeout(this.refreshTimer);
    const delay = Math.max(expiresAtMs - Date.now() - REFRESH_LEEWAY_MS, 5_000);
    this.refreshTimer = setTimeout(() => {
      this.refresh().subscribe({ error: () => this.expireSession() });
    }, delay);
  }
}
