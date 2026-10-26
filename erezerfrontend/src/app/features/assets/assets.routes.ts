import { Routes } from '@angular/router';
import { requirePermission } from '../../core/auth/auth.guards';
import { unsavedChangesGuard } from '../../shared/unsaved-changes.guard';

export const ASSET_ROUTES: Routes = [
  {
    path: '',
    title: 'Assets',
    canActivate: [requirePermission('ASSET_VIEW')],
    loadComponent: () => import('./asset-list/asset-list-page').then((m) => m.AssetListPage),
  },
  {
    path: 'new',
    title: 'Add Asset',
    canActivate: [requirePermission('ASSET_CREATE')],
    canDeactivate: [unsavedChangesGuard],
    loadComponent: () => import('./asset-form/asset-form-page').then((m) => m.AssetFormPage),
  },
  {
    path: ':id',
    title: 'Asset',
    canActivate: [requirePermission('ASSET_VIEW')],
    loadComponent: () => import('./asset-detail/asset-detail-page').then((m) => m.AssetDetailPage),
  },
  {
    path: ':id/edit',
    title: 'Edit Asset',
    canActivate: [requirePermission('ASSET_MANAGE')],
    canDeactivate: [unsavedChangesGuard],
    loadComponent: () => import('./asset-form/asset-form-page').then((m) => m.AssetFormPage),
  },
];
