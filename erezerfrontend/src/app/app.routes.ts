import { inject } from '@angular/core';
import { Routes } from '@angular/router';
import { authGuard, guestGuard, requirePermission } from './core/auth/auth.guards';
import { AuthService } from './core/auth/auth.service';
import { NAV_ITEMS } from './layout/nav-items';
import { Shell } from './layout/shell/shell';

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
    path: 'forgot-password',
    title: 'Forgot password',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/auth/password-reset/forgot-password-page').then((m) => m.ForgotPasswordPage),
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
        loadComponent: () => import('./features/dashboard/dashboard-page').then((m) => m.DashboardPage),
      },
      {
        path: 'orders',
        loadChildren: () => import('./features/orders/orders.routes').then((m) => m.ORDER_ROUTES),
      },
      {
        path: 'sold-products',
        title: 'Sold Products',
        canActivate: [requirePermission('SALES_VIEW')],
        loadComponent: () => import('./features/sold-products/sold-products-page').then((m) => m.SoldProductsPage),
      },
      {
        path: 'stock',
        title: 'Stock',
        canActivate: [requirePermission('STOCK_VIEW')],
        loadComponent: () => import('./features/stock/stock-page').then((m) => m.StockPage),
      },
      {
        path: 'customers',
        title: 'Customers',
        canActivate: [requirePermission('CUSTOMER_VIEW')],
        loadComponent: () => import('./features/customers/customer-list-page').then((m) => m.CustomerListPage),
      },
      {
        path: 'expenses',
        loadChildren: () => import('./features/expenses/expenses.routes').then((m) => m.EXPENSE_ROUTES),
      },
      {
        path: 'assets',
        loadChildren: () => import('./features/assets/assets.routes').then((m) => m.ASSET_ROUTES),
      },
      {
        path: 'notifications',
        title: 'Notifications',
        canActivate: [requirePermission('NOTIFICATION_VIEW')],
        loadComponent: () => import('./features/notifications/notifications-page').then((m) => m.NotificationsPage),
      },
      {
        path: 'boost',
        title: 'Expense (Boost)',
        canActivate: [requirePermission('BOOST_VIEW')],
        loadComponent: () => import('./features/boost/boost-page').then((m) => m.BoostPage),
      },
      {
        path: 'daily-expenses',
        title: 'Expense (Daily)',
        canActivate: [requirePermission('DAILY_EXPENSE_CREATE', 'DAILY_EXPENSE_VIEW')],
        loadComponent: () => import('./features/daily-expenses/daily-expense-page').then((m) => m.DailyExpensePage),
      },
      {
        path: 'employees',
        loadChildren: () => import('./features/employees/employees.routes').then((m) => m.EMPLOYEE_ROUTES),
      },
      {
        path: 'my-attendance',
        title: 'My Attendance',
        canActivate: [requirePermission('ATTENDANCE_MARK')],
        loadComponent: () => import('./features/attendance/my-attendance-page').then((m) => m.MyAttendancePage),
      },
      {
        path: 'attendance',
        title: 'Attendance',
        canActivate: [requirePermission('ATTENDANCE_VIEW')],
        loadComponent: () => import('./features/attendance/attendance-page').then((m) => m.AttendancePage),
      },
      {
        path: 'settings/courier',
        title: 'Courier settings',
        canActivate: [requirePermission('COURIER_SETTINGS')],
        loadComponent: () => import('./features/courier/courier-settings-page').then((m) => m.CourierSettingsPage),
      },
      {
        path: 'staff',
        loadChildren: () => import('./features/staff/staff.routes').then((m) => m.STAFF_ROUTES),
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
