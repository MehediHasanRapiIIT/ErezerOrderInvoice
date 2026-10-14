import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** Temporary page for sections scheduled in later build steps. Inputs are bound from route data. */
@Component({
  selector: 'app-coming-soon-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1 class="page-title">{{ title() }}</h1>
    <div class="card mt-6 flex flex-col items-center px-6 py-16 text-center">
      <span class="mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-brand/15 text-brand-light">
        <i class="fas text-2xl" [class]="icon()"></i>
      </span>
      <p class="text-lg font-semibold text-white">Coming soon</p>
      <p class="mt-1 max-w-md text-sm text-slate-400">{{ description() }}</p>
    </div>
  `,
})
export class ComingSoonPage {
  readonly title = input.required<string>();
  readonly icon = input('fa-tools');
  readonly description = input('This section is under construction.');
}
