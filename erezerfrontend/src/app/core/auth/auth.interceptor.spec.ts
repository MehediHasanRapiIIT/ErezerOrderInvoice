import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { TokenResponse } from './auth.models';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';

function tokenResponse(accessToken: string): TokenResponse {
  return {
    accessToken,
    tokenType: 'Bearer',
    expiresAt: new Date(Date.now() + 15 * 60_000).toISOString(),
    user: { id: 1, fullName: 'Test User', email: 't@test.local', role: 'MODERATOR', permissions: ['ORDER_VIEW'] },
  };
}

describe('authInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let auth: AuthService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
  });

  afterEach(() => backend.verify());

  function signIn(token: string): void {
    auth.login({ email: 't@test.local', password: 'x' }).subscribe();
    backend.expectOne('/api/auth/login').flush(tokenResponse(token));
  }

  it('attaches the bearer token to API requests', () => {
    signIn('token-1');
    http.get('/api/orders').subscribe();
    const req = backend.expectOne('/api/orders');
    expect(req.request.headers.get('Authorization')).toBe('Bearer token-1');
    req.flush([]);
  });

  it('does not attach the token to non-API requests', () => {
    signIn('token-1');
    http.get('/assets/config.json').subscribe();
    const req = backend.expectOne('/assets/config.json');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({});
  });

  it('refreshes once on 401 and retries with the new token', () => {
    signIn('expired');
    let result: unknown;
    http.get('/api/orders').subscribe((body) => (result = body));

    backend.expectOne('/api/orders').flush(null, { status: 401, statusText: 'Unauthorized' });
    backend.expectOne('/api/auth/refresh').flush(tokenResponse('fresh'));

    const retry = backend.expectOne('/api/orders');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer fresh');
    retry.flush(['ok']);
    expect(result).toEqual(['ok']);
  });

  it('signs out and goes to the login page when the refresh fails', () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    signIn('expired');
    http.get('/api/orders').subscribe({ error: () => undefined });

    backend.expectOne('/api/orders').flush(null, { status: 401, statusText: 'Unauthorized' });
    backend.expectOne('/api/auth/refresh').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(auth.isAuthenticated()).toBe(false);
    expect(navigate).toHaveBeenCalledWith(['/login'], expect.anything());
  });
});
