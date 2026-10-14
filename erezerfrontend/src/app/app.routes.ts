import { inject } from '@angular/core';
import { Routes } from '@angular/router';
import { authGuard, guestGuard, requirePermission } from './core/auth/auth.guards';
import { AuthService } from './core/auth/auth.service';
import { NAV_ITEMS } from './layout/nav-items';
import { Shell } from './layout/shell/shell';

const comingSoon = () => import('./shared/coming-soon/coming-soon-page').then((m) => m.ComingSoonPage);

/** Sends the user to the first section their permissions allow. */
function landingPage(): string {
  const auth = inject(AuthService);
  return NAV_ITEMS.find((item) => auth.hasAnyPermission(item.permissions))?.route ?? '/forbidden';
}

export const routes: Routes = [
  {
    path: 'login',
    title: 'Sign in',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/auth/login/login-page').then((m) => m.LoginPage),
  },
  {
    path: '',
    component: Shell,
    canMatch: [authGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: landingPage },
      {
        path: 'dashboard',
        title: 'Dashboard',
        canActivate: [requirePermission('DASHBOARD_VIEW')],
        loadComponent: comingSoon,
        data: {
          title: 'Dashboard',
          icon: 'fa-chart-pie',
          description: 'Revenue, delivery and return totals will appear here.',
        },
      },
      {
        path: 'orders',
        loadChildren: () => import('./features/orders/orders.routes').then((m) => m.ORDER_ROUTES),
      },
      {
        path: 'expenses',
        title: 'Expenses',
        canActivate: [requirePermission('EXPENSE_VIEW')],
        loadComponent: comingSoon,
        data: {
          title: 'Expenses',
          icon: 'fa-wallet',
          description: 'A searchable, filterable list of expenses with an Add Expense form.',
        },
      },
      {
        path: 'staff',
        title: 'Staff Management',
        canActivate: [requirePermission('STAFF_MANAGE')],
        loadComponent: comingSoon,
        data: {
          title: 'Staff Management',
          icon: 'fa-users-cog',
          description: 'Add moderators and choose exactly what each one is allowed to do.',
        },
      },
      {
        path: 'account/password',
        title: 'Change password',
        loadComponent: () =>
          import('./features/auth/change-password/change-password-page').then((m) => m.ChangePasswordPage),
      },
      {
        path: 'forbidden',
        title: 'No access',
        loadComponent: () => import('./features/errors/error-pages').then((m) => m.ForbiddenPage),
      },
      {
        path: '**',
        title: 'Not found',
        loadComponent: () => import('./features/errors/error-pages').then((m) => m.NotFoundPage),
      },
    ],
  },
];
