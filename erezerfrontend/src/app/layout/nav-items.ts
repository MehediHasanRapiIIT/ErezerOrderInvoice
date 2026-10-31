import { Permission } from '../core/auth/auth.models';

export interface NavItem {
  label: string;
  icon: string;
  route: string;
  /** Shown when the user holds any of these. */
  permissions: Permission[];
  /** Not shown to admins, who hold every permission but don't give attendance. */
  notForAdmins?: boolean;
}

export const NAV_ITEMS: readonly NavItem[] = [
  { label: 'Dashboard', icon: 'fa-chart-pie', route: '/dashboard', permissions: ['DASHBOARD_VIEW'] },
  { label: 'Orders', icon: 'fa-receipt', route: '/orders', permissions: ['ORDER_VIEW', 'ORDER_CREATE'] },
  { label: 'Sold Products', icon: 'fa-chart-bar', route: '/sold-products', permissions: ['SALES_VIEW'] },
  { label: 'Stock', icon: 'fa-boxes', route: '/stock', permissions: ['STOCK_VIEW'] },
  { label: 'Customers', icon: 'fa-address-book', route: '/customers', permissions: ['CUSTOMER_VIEW'] },
  { label: 'Expenses', icon: 'fa-wallet', route: '/expenses', permissions: ['EXPENSE_VIEW'] },
  { label: 'Expense (Daily)', icon: 'fa-money-bill-wave', route: '/daily-expenses', permissions: ['DAILY_EXPENSE_CREATE', 'DAILY_EXPENSE_VIEW'] },
  { label: 'Expense (Boost)', icon: 'fa-bullhorn', route: '/boost', permissions: ['BOOST_VIEW'] },
  { label: 'Assets', icon: 'fa-chair', route: '/assets', permissions: ['ASSET_VIEW'] },
  { label: 'Employees', icon: 'fa-id-badge', route: '/employees', permissions: ['EMPLOYEE_VIEW'] },
  { label: 'My Attendance', icon: 'fa-fingerprint', route: '/my-attendance', permissions: ['ATTENDANCE_MARK'], notForAdmins: true },
  { label: 'Attendance', icon: 'fa-user-clock', route: '/attendance', permissions: ['ATTENDANCE_VIEW'] },
  { label: 'Notifications', icon: 'fa-bell', route: '/notifications', permissions: ['NOTIFICATION_VIEW'] },
  { label: 'Courier Settings', icon: 'fa-shipping-fast', route: '/settings/courier', permissions: ['COURIER_SETTINGS'] },
  { label: 'Staff Management', icon: 'fa-users-cog', route: '/staff', permissions: ['STAFF_MANAGE'] },
];
