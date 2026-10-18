import { ChangeDetectionStrategy, Component, HostListener, inject, input, output, signal } from '@angular/core';
import { errorMessage } from '../../../core/http/api-error';
import { ToastService } from '../../../core/notify/toast.service';
import { generatePassword, PASSWORD_PATTERN } from '../../../shared/password';
import { StaffApi } from '../data/staff.api';
import { StaffMember } from '../data/staff.models';

/** Sets a new password for a staff member and shows it once so the admin can pass it on. */
@Component({
  selector: 'app-reset-password-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="fixed inset-0 z-[85] flex items-center justify-center bg-black/60 p-4" (click)="close()">
      <div class="card w-full max-w-md p-6 shadow-2xl" role="dialog" aria-modal="true" aria-label="Reset password" (click)="$event.stopPropagation()">
        <h2 class="text-lg font-semibold text-white">Reset password</h2>
        <p class="mt-1 text-sm text-slate-400">
          for <span class="text-white">{{ member().fullName }}</span>. They will be signed out on every device and their
          account is unlocked.
        </p>

        @if (!done()) {
          <label class="mt-5 block">
            <span class="field-label">New password</span>
            <div class="flex gap-2">
              <input class="field font-mono" [value]="password()" (input)="password.set($any($event.target).value)" aria-label="New password" />
              <button type="button" class="btn-secondary shrink-0 px-3" (click)="password.set(generate())" title="Generate another" aria-label="Generate another password">
                <i class="fas fa-dice"></i>
              </button>
            </div>
            <span class="mt-1 block text-xs" [class]="valid() ? 'text-slate-500' : 'text-red-300'">10–72 characters, with a letter and a digit.</span>
          </label>
          <div class="mt-6 flex justify-end gap-2">
            <button type="button" class="btn-secondary" (click)="close()">Cancel</button>
            <button type="button" class="btn-primary" [disabled]="!valid() || saving()" (click)="save()">
              @if (saving()) { <i class="fas fa-circle-notch fa-spin"></i> }
              Reset password
            </button>
          </div>
        } @else {
          <div class="mt-5 rounded-lg border border-emerald-700 bg-emerald-950/50 p-4">
            <p class="text-sm text-emerald-200">Password changed. Share it with them privately; it won't be shown again.</p>
            <div class="mt-3 flex items-center gap-2">
              <code class="flex-1 rounded bg-app px-3 py-2 font-mono text-white">{{ password() }}</code>
              <button type="button" class="btn-secondary px-3" (click)="copy()" aria-label="Copy password"><i class="fas" [class.fa-copy]="!copied()" [class.fa-check]="copied()"></i></button>
            </div>
          </div>
          <div class="mt-6 flex justify-end">
            <button type="button" class="btn-primary" (click)="close()">Done</button>
          </div>
        }
      </div>
    </div>
  `,
})
export class ResetPasswordDialog {
  readonly member = input.required<StaffMember>();
  readonly closed = output<void>();

  private readonly api = inject(StaffApi);
  private readonly toast = inject(ToastService);

  protected readonly password = signal(generatePassword());
  protected readonly saving = signal(false);
  protected readonly done = signal(false);
  protected readonly copied = signal(false);
  protected readonly generate = generatePassword;

  protected valid(): boolean {
    return PASSWORD_PATTERN.test(this.password());
  }

  protected save(): void {
    this.saving.set(true);
    this.api.resetPassword(this.member().id, this.password()).subscribe({
      next: () => {
        this.saving.set(false);
        this.done.set(true);
      },
      error: (err: unknown) => {
        this.saving.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected copy(): void {
    void navigator.clipboard?.writeText(this.password()).then(() => this.copied.set(true));
  }

  @HostListener('document:keydown.escape')
  protected close(): void {
    if (!this.saving()) {
      this.closed.emit();
    }
  }
}
