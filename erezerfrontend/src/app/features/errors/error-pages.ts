import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-forbidden-page',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="card mx-auto mt-10 max-w-lg px-6 py-12 text-center">
      <i class="fas fa-lock mb-4 text-4xl text-amber-400"></i>
      <h1 class="text-xl font-semibold text-white">You don't have access to this page</h1>
      <p class="mt-2 text-sm text-slate-400">Ask an administrator to grant you the permission if you need it.</p>
      <a routerLink="/" class="btn-secondary mt-6">Go to start page</a>
    </div>
  `,
})
export class ForbiddenPage {}

@Component({
  selector: 'app-not-found-page',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="card mx-auto mt-10 max-w-lg px-6 py-12 text-center">
      <p class="mb-2 text-5xl font-bold text-slate-600">404</p>
      <h1 class="text-xl font-semibold text-white">Page not found</h1>
      <a routerLink="/" class="btn-secondary mt-6">Go to start page</a>
    </div>
  `,
})
export class NotFoundPage {}
