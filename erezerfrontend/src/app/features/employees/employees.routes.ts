import { Routes } from '@angular/router';
import { requirePermission } from '../../core/auth/auth.guards';
import { unsavedChangesGuard } from '../../shared/unsaved-changes.guard';

export const EMPLOYEE_ROUTES: Routes = [
  {
    path: '',
    title: 'Employees',
    canActivate: [requirePermission('EMPLOYEE_VIEW')],
    loadComponent: () => import('./employee-list-page').then((m) => m.EmployeeListPage),
  },
  {
    path: 'new',
    title: 'Register Employee',
    canActivate: [requirePermission('EMPLOYEE_CREATE')],
    canDeactivate: [unsavedChangesGuard],
    loadComponent: () => import('./employee-form-page').then((m) => m.EmployeeFormPage),
  },
  {
    path: ':id',
    title: 'Employee',
    canActivate: [requirePermission('EMPLOYEE_VIEW')],
    loadComponent: () => import('./employee-detail-page').then((m) => m.EmployeeDetailPage),
  },
  {
    path: ':id/edit',
    title: 'Edit Employee',
    canActivate: [requirePermission('EMPLOYEE_EDIT')],
    canDeactivate: [unsavedChangesGuard],
    loadComponent: () => import('./employee-form-page').then((m) => m.EmployeeFormPage),
  },
];
