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
    path: 'exchange',
    title: 'Exchange products',
    canActivate: [requirePermission('EXCHANGE_CREATE')],
    loadComponent: () => import('./exchange/exchange-page').then((m) => m.ExchangePage),
  },
  {
    path: 'return',
    title: 'Return products',
    canActivate: [requirePermission('RETURN_CREATE')],
    loadComponent: () => import('./return/return-page').then((m) => m.ReturnPage),
  },
  {
    path: 'send-to-courier',
    title: 'Send to Steadfast',
    canActivate: [requirePermission('COURIER_SEND')],
    loadComponent: () => import('../courier/bulk-send-page').then((m) => m.BulkSendPage),
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
