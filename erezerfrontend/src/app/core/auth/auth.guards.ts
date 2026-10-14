import { inject } from '@angular/core';
import { CanActivateFn, CanMatchFn, Router } from '@angular/router';
import { Permission } from './auth.models';
import { AuthService } from './auth.service';

/** Requires a signed-in user; otherwise redirects to /login and remembers where they were going. */
export const authGuard: CanMatchFn = (_route, segments) => {
  if (inject(AuthService).isAuthenticated()) {
    return true;
  }
  const returnUrl = '/' + segments.map((s) => s.path).join('/');
  return inject(Router).createUrlTree(['/login'], {
    queryParams: returnUrl === '/' ? undefined : { returnUrl },
  });
};

/** Keeps signed-in users away from the login page. */
export const guestGuard: CanActivateFn = () =>
  inject(AuthService).isAuthenticated() ? inject(Router).createUrlTree(['/']) : true;

/** Requires at least one of the given permissions. The backend enforces the same rule; this only tidies the UI. */
export function requirePermission(...permissions: Permission[]): CanActivateFn {
  return () =>
    inject(AuthService).hasAnyPermission(permissions) ? true : inject(Router).createUrlTree(['/forbidden']);
}
