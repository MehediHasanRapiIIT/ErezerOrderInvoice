/** Mirrors com.erezer.admin.security.Permission on the backend. */
export type Permission =
  | 'DASHBOARD_VIEW'
  | 'ORDER_VIEW'
  | 'ORDER_CREATE'
  | 'ORDER_EDIT'
  | 'ORDER_STATUS_UPDATE'
  | 'ORDER_STATUS_OVERRIDE'
  | 'ORDER_NOTE_ADD'
  | 'ORDER_COURIER_COST'
  | 'ORDER_DATE_EDIT'
  | 'ORDER_DELETE'
  | 'EXCHANGE_CREATE'
  | 'RETURN_CREATE'
  | 'EXPENSE_VIEW'
  | 'EXPENSE_CREATE'
  | 'EXPENSE_EDIT'
  | 'EXPENSE_DELETE'
  | 'NOTIFICATION_VIEW'
  | 'NOTIFICATION_MANAGE'
  | 'BOOST_VIEW'
  | 'BOOST_COST_ADD'
  | 'BOOST_BUY'
  | 'BOOST_EDIT'
  | 'BOOST_DELETE'
  | 'DAILY_EXPENSE_CREATE'
  | 'DAILY_EXPENSE_VIEW'
  | 'DAILY_EXPENSE_APPROVE'
  | 'DAILY_EXPENSE_EDIT'
  | 'DAILY_EXPENSE_DELETE'
  | 'DAILY_EXPENSE_CATEGORIES'
  | 'EMPLOYEE_VIEW'
  | 'EMPLOYEE_CREATE'
  | 'EMPLOYEE_EDIT'
  | 'EMPLOYEE_DELETE'
  | 'ATTENDANCE_MARK'
  | 'ATTENDANCE_VIEW'
  | 'ATTENDANCE_APPROVE'
  | 'ATTENDANCE_EDIT'
  | 'ATTENDANCE_SETTINGS'
  | 'ASSET_VIEW'
  | 'ASSET_COSTS_VIEW'
  | 'ASSET_HISTORY_VIEW'
  | 'ASSET_CREATE'
  | 'ASSET_MANAGE'
  | 'ASSET_STATUS'
  | 'ASSET_REPAIRS'
  | 'ASSET_CATEGORIES'
  | 'ASSET_EXPORT'
  | 'ASSET_DELETE'
  | 'SALES_VIEW'
  | 'STOCK_VIEW'
  | 'STOCK_MANAGE'
  | 'SIZE_MANAGE'
  | 'CUSTOMER_VIEW'
  | 'CUSTOMER_EXPORT'
  | 'COURIER_SEND'
  | 'COURIER_SETTINGS'
  | 'STAFF_MANAGE';

export type Role = 'ADMIN' | 'MODERATOR' | 'EMPLOYEE';

export const ROLE_LABELS: Record<Role, string> = {
  ADMIN: 'Admin',
  MODERATOR: 'Moderator',
  EMPLOYEE: 'Employee',
};

export interface CurrentUser {
  id: number;
  fullName: string;
  email: string;
  phone?: string;
  role: Role;
  permissions: Permission[];
}

export interface TokenResponse {
  accessToken: string;
  tokenType: 'Bearer';
  expiresAt: string;
  user: CurrentUser;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}
