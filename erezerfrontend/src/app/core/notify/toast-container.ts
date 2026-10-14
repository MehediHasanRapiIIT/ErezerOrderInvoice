import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ToastService } from './toast.service';

@Component({
  selector: 'app-toast-container',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="fixed top-4 right-4 z-[100] flex w-80 max-w-[calc(100vw-2rem)] flex-col gap-2" aria-live="polite">
      @for (toast of toasts.toasts(); track toast.id) {
        <div
          class="animate-fade-in-down flex items-start gap-3 rounded-lg border px-4 py-3 text-sm shadow-lg"
          [class]="styles[toast.kind]"
          role="status"
        >
          <i class="fas mt-0.5" [class]="icons[toast.kind]"></i>
          <p class="flex-1">{{ toast.message }}</p>
          <button
            type="button"
            class="opacity-70 hover:opacity-100 cursor-pointer"
            (click)="toasts.dismiss(toast.id)"
            aria-label="Dismiss"
          >
            <i class="fas fa-times"></i>
          </button>
        </div>
      }
    </div>
  `,
})
export class ToastContainer {
  protected readonly toasts = inject(ToastService);

  protected readonly styles = {
    success: 'bg-emerald-950 border-emerald-700 text-emerald-100',
    error: 'bg-red-950 border-red-700 text-red-100',
    info: 'bg-slate-800 border-slate-600 text-slate-100',
  };

  protected readonly icons = {
    success: 'fa-check-circle',
    error: 'fa-exclamation-circle',
    info: 'fa-info-circle',
  };
}
