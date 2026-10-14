import { Permission } from '../core/auth/auth.models';

export interface NavItem {
  label: string;
  icon: string;
  route: string;
  /** Shown when the user holds any of these. */
  permissions: Permission[];
}

export const NAV_ITEMS: readonly NavItem[] = [
  { label: 'Dashboard', icon: 'fa-chart-pie', route: '/dashboard', permissions: ['DASHBOARD_VIEW'] },
  { label: 'Orders', icon: 'fa-receipt', route: '/orders', permissions: ['ORDER_VIEW', 'ORDER_CREATE'] },
  { label: 'Expenses', icon: 'fa-wallet', route: '/expenses', permissions: ['EXPENSE_VIEW'] },
  { label: 'Staff Management', icon: 'fa-users-cog', route: '/staff', permissions: ['STAFF_MANAGE'] },
];
