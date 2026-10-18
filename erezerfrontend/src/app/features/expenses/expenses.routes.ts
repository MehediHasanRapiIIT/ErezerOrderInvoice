import { Routes } from '@angular/router';
import { requirePermission } from '../../core/auth/auth.guards';
import { unsavedChangesGuard } from '../../shared/unsaved-changes.guard';

export const EXPENSE_ROUTES: Routes = [
  {
    path: '',
    title: 'Expenses',
    canActivate: [requirePermission('EXPENSE_VIEW')],
    loadComponent: () => import('./expense-list/expense-list-page').then((m) => m.ExpenseListPage),
  },
  {
    path: 'new',
    title: 'Add Expense',
    canActivate: [requirePermission('EXPENSE_CREATE')],
    canDeactivate: [unsavedChangesGuard],
    loadComponent: () => import('./expense-form/expense-form-page').then((m) => m.ExpenseFormPage),
  },
  {
    path: ':id/edit',
    title: 'Edit Expense',
    canActivate: [requirePermission('EXPENSE_EDIT')],
    canDeactivate: [unsavedChangesGuard],
    loadComponent: () => import('./expense-form/expense-form-page').then((m) => m.ExpenseFormPage),
  },
];
