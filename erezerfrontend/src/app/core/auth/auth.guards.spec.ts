import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, provideRouter, RouterStateSnapshot, UrlSegment, UrlTree } from '@angular/router';
import { authGuard, requirePermission } from './auth.guards';
import { Permission } from './auth.models';
import { AuthService } from './auth.service';

describe('auth guards', () => {
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    backend = TestBed.inject(HttpTestingController);
  });

  function signInWith(permissions: Permission[]): void {
    TestBed.inject(AuthService).login({ email: 'a@b.c', password: 'x' }).subscribe();
    backend.expectOne('/api/auth/login').flush({
      accessToken: 't',
      tokenType: 'Bearer',
      expiresAt: new Date(Date.now() + 60 * 60_000).toISOString(),
      user: { id: 1, fullName: 'A', email: 'a@b.c', role: 'MODERATOR', permissions },
    });
  }

  function runAuthGuard(path: string): unknown {
    return TestBed.runInInjectionContext(() => authGuard({}, [new UrlSegment(path, {})]));
  }

  function runPermissionGuard(...permissions: Permission[]): unknown {
    return TestBed.runInInjectionContext(() =>
      requirePermission(...permissions)({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot),
    );
  }

  it('redirects anonymous users to login with a return URL', () => {
    const result = runAuthGuard('orders') as UrlTree;
    expect(result.toString()).toBe('/login?returnUrl=%2Forders');
  });

  it('lets signed-in users through', () => {
    signInWith([]);
    expect(runAuthGuard('orders')).toBe(true);
  });

  it('allows a route when the user has any listed permission', () => {
    signInWith(['ORDER_CREATE']);
    expect(runPermissionGuard('ORDER_VIEW', 'ORDER_CREATE')).toBe(true);
  });

  it('sends users without the permission to /forbidden', () => {
    signInWith(['ORDER_VIEW']);
    const result = runPermissionGuard('STAFF_MANAGE') as UrlTree;
    expect(result.toString()).toBe('/forbidden');
  });
});
