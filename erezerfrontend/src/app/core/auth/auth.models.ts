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
  | 'ORDER_DELETE'
  | 'EXCHANGE_CREATE'
  | 'RETURN_CREATE'
  | 'EXPENSE_VIEW'
  | 'EXPENSE_CREATE'
  | 'EXPENSE_EDIT'
  | 'EXPENSE_DELETE'
  | 'ASSET_VIEW'
  | 'ASSET_MANAGE'
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

export type Role = 'ADMIN' | 'MODERATOR';

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
