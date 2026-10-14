import { ChangeDetectionStrategy, Component, HostListener, inject, Injectable, signal } from '@angular/core';

export interface ConfirmOptions {
  title: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  danger?: boolean;
}

interface PendingConfirm extends ConfirmOptions {
  resolve: (confirmed: boolean) => void;
}

/** Promise-based replacement for window.confirm, rendered in the app's own style. */
@Injectable({ providedIn: 'root' })
export class ConfirmService {
  readonly pending = signal<PendingConfirm | null>(null);

  ask(options: ConfirmOptions): Promise<boolean> {
    this.pending()?.resolve(false);
    return new Promise((resolve) => this.pending.set({ ...options, resolve }));
  }

  answer(confirmed: boolean): void {
    this.pending()?.resolve(confirmed);
    this.pending.set(null);
  }
}

@Component({
  selector: 'app-confirm-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (confirm.pending(); as dialog) {
      <div class="fixed inset-0 z-[90] flex items-center justify-center bg-black/60 p-4" (click)="confirm.answer(false)">
        <div
          class="card w-full max-w-md p-6 shadow-2xl"
          role="alertdialog"
          aria-modal="true"
          [attr.aria-label]="dialog.title"
          (click)="$event.stopPropagation()"
        >
          <h2 class="text-lg font-semibold text-white">{{ dialog.title }}</h2>
          <p class="mt-2 text-sm text-slate-300">{{ dialog.message }}</p>
          <div class="mt-6 flex justify-end gap-2">
            <button type="button" class="btn-secondary" (click)="confirm.answer(false)">
              {{ dialog.cancelLabel ?? 'Cancel' }}
            </button>
            <button
              type="button"
              class="btn-primary"
              [class.btn-danger]="dialog.danger"
              (click)="confirm.answer(true)"
              autofocus
            >
              {{ dialog.confirmLabel ?? 'Confirm' }}
            </button>
          </div>
        </div>
      </div>
    }
  `,
})
export class ConfirmDialog {
  protected readonly confirm = inject(ConfirmService);

  @HostListener('document:keydown.escape')
  protected onEscape(): void {
    if (this.confirm.pending()) {
      this.confirm.answer(false);
    }
  }
}
