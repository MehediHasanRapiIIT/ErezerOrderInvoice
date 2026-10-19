import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { errorMessage } from '../../../core/http/api-error';
import { PasswordResetApi } from './password-reset.api';

/** Asks for the account's email; an admin then sets a new password on the Staff page. */
@Component({
  selector: 'app-forgot-password-page',
  imports: [ReactiveFormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="flex min-h-screen items-center justify-center bg-app px-4">
      <div class="w-full max-w-sm">
        <div class="mb-8 text-center">
          <h1 class="text-xl font-semibold text-white">Forgot your password?</h1>
          <p class="mt-1 text-sm text-slate-400">Enter your account's email. An admin will set a new password for you.</p>
        </div>

        @if (sentTo(); as email) {
          <div class="space-y-4 rounded-xl border border-line bg-panel p-6 text-sm shadow-xl" role="status">
            <p class="flex items-start gap-2 text-emerald-300"><i class="fas fa-check-circle mt-0.5"></i>
              <span>Request sent. If <b>{{ email }}</b> belongs to an account, the admins can now see it on the Staff page.</span></p>
            <p class="text-slate-400">An admin will set a new password and give it to you. You can change it after signing in.</p>
            <a routerLink="/login" class="btn-secondary w-full justify-center">Back to sign in</a>
          </div>
        } @else {
          <form [formGroup]="form" (ngSubmit)="submit()" class="space-y-5 rounded-xl border border-line bg-panel p-6 shadow-xl" novalidate>
            @if (error(); as message) {
              <div class="flex items-start gap-2 rounded-md border border-red-800 bg-red-950/60 px-3 py-2 text-sm text-red-200" role="alert">
                <i class="fas fa-exclamation-circle mt-0.5"></i><span>{{ message }}</span>
              </div>
            }
            <label class="block">
              <span class="mb-1.5 block text-sm font-medium text-slate-300">Email</span>
              <input type="email" formControlName="email" autocomplete="username" class="field" autofocus
                     [class.field-invalid]="form.controls.email.invalid && form.controls.email.touched" />
              @if (form.controls.email.invalid && form.controls.email.touched) {
                <span class="mt-1 block text-xs text-red-300">Enter a valid email address</span>
              }
            </label>
            <button type="submit" class="btn-primary w-full" [disabled]="submitting()">
              @if (submitting()) { <i class="fas fa-circle-notch fa-spin"></i> Sending… } @else { Ask an admin to reset it }
            </button>
            <a routerLink="/login" class="block text-center text-sm text-slate-400 hover:text-white">Back to sign in</a>
          </form>
        }
      </div>
    </div>
  `,
})
export class ForgotPasswordPage {
  private readonly api = inject(PasswordResetApi);

  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly sentTo = signal<string | null>(null);
  protected readonly form = inject(NonNullableFormBuilder).group({
    email: ['', [Validators.required, Validators.email]],
  });

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const email = this.form.controls.email.value.trim();
    this.submitting.set(true);
    this.error.set(null);
    this.api.forgot(email).subscribe({
      next: () => {
        this.submitting.set(false);
        this.sentTo.set(email);
      },
      error: (err: unknown) => {
        this.submitting.set(false);
        this.error.set(errorMessage(err));
      },
    });
  }
}
