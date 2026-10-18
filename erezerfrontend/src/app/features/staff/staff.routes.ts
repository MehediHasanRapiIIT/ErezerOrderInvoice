import { Routes } from '@angular/router';
import { requirePermission } from '../../core/auth/auth.guards';
import { unsavedChangesGuard } from '../../shared/unsaved-changes.guard';

export const STAFF_ROUTES: Routes = [
  {
    path: '',
    title: 'Staff Management',
    canActivate: [requirePermission('STAFF_MANAGE')],
    loadComponent: () => import('./staff-list/staff-list-page').then((m) => m.StaffListPage),
  },
  {
    path: 'new',
    title: 'Add Staff',
    canActivate: [requirePermission('STAFF_MANAGE')],
    canDeactivate: [unsavedChangesGuard],
    loadComponent: () => import('./staff-form/staff-form-page').then((m) => m.StaffFormPage),
  },
  {
    path: ':id/edit',
    title: 'Edit Staff',
    canActivate: [requirePermission('STAFF_MANAGE')],
    canDeactivate: [unsavedChangesGuard],
    loadComponent: () => import('./staff-form/staff-form-page').then((m) => m.StaffFormPage),
  },
];
