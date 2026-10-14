import { Routes } from '@angular/router';
import { requirePermission } from '../../core/auth/auth.guards';
import { unsavedChangesGuard } from '../../shared/unsaved-changes.guard';

export const ORDER_ROUTES: Routes = [
  {
    path: '',
    title: 'Orders',
    canActivate: [requirePermission('ORDER_VIEW')],
    loadComponent: () => import('./order-list/order-list-page').then((m) => m.OrderListPage),
  },
  {
    path: 'new',
    title: 'Add Order',
    canActivate: [requirePermission('ORDER_CREATE')],
    canDeactivate: [unsavedChangesGuard],
    loadComponent: () => import('./order-form/order-form-page').then((m) => m.OrderFormPage),
  },
  {
    path: ':id',
    title: 'Order',
    canActivate: [requirePermission('ORDER_VIEW')],
    loadComponent: () => import('./order-detail/order-detail-page').then((m) => m.OrderDetailPage),
  },
  {
    path: ':id/edit',
    title: 'Edit Order',
    canActivate: [requirePermission('ORDER_EDIT')],
    canDeactivate: [unsavedChangesGuard],
    loadComponent: () => import('./order-form/order-form-page').then((m) => m.OrderFormPage),
  },
];
