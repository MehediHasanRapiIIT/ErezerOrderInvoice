import { Permission, Role } from '../../../core/auth/auth.models';

export interface StaffMember {
  id: number;
  fullName: string;
  email: string;
  phone?: string;
  role: Role;
  active: boolean;
  locked: boolean;
  permissions: Permission[];
  lastLoginAt?: string;
  createdAt: string;
  version: number;
}

/** Mirrors the backend permission catalog (GET /api/permissions). */
export interface PermissionInfo {
  name: Permission;
  group: string;
  label: string;
  adminOnly: boolean;
  /** Permissions that come with this one (e.g. "Add and edit assets" includes "View assets"). */
  includes: Permission[];
}

export interface CreateStaffRequest {
  fullName: string;
  email: string;
  phone: string | null;
  role: Role;
  password: string;
  permissions: Permission[];
}

export interface UpdateStaffRequest {
  fullName: string;
  phone: string | null;
  role: Role;
  permissions: Permission[];
  version: number;
}

/** A "forgot password" request from the sign-in page, waiting for an admin. */
export interface PasswordRequest {
  id: number;
  userId: number;
  fullName: string;
  email: string;
  role: Role;
  requestedAt: string;
}
