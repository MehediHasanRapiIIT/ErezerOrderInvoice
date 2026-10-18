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
  { label: 'Customers', icon: 'fa-address-book', route: '/customers', permissions: ['CUSTOMER_VIEW'] },
  { label: 'Expenses', icon: 'fa-wallet', route: '/expenses', permissions: ['EXPENSE_VIEW'] },
  { label: 'Courier Settings', icon: 'fa-shipping-fast', route: '/settings/courier', permissions: ['COURIER_SETTINGS'] },
  { label: 'Staff Management', icon: 'fa-users-cog', route: '/staff', permissions: ['STAFF_MANAGE'] },
];
