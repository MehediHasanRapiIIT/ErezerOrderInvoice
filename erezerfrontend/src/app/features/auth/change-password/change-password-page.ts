import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import {
  AbstractControl,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { AuthService } from '../../../core/auth/auth.service';
import { errorMessage } from '../../../core/http/api-error';
import { ToastService } from '../../../core/notify/toast.service';

/** Mirrors the backend PasswordPolicy: 10-72 characters with at least one letter and one digit. */
const PASSWORD_PATTERN = /^(?=.*[A-Za-z])(?=.*\d).{10,72}$/;

function matchesNewPassword(group: AbstractControl): ValidationErrors | null {
  const { newPassword, confirmPassword } = group.value as Record<string, string>;
  return newPassword === confirmPassword ? null : { mismatch: true };
}

@Component({
  selector: 'app-change-password-page',
  imports: [ReactiveFormsModule],
  templateUrl: './change-password-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChangePasswordPage {
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);

  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group(
    {
      currentPassword: ['', Validators.required],
      newPassword: ['', [Validators.required, Validators.pattern(PASSWORD_PATTERN)]],
      confirmPassword: ['', Validators.required],
    },
    { validators: matchesNewPassword },
  );

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { currentPassword, newPassword } = this.form.getRawValue();
    this.submitting.set(true);
    this.error.set(null);

    this.auth.changePassword({ currentPassword, newPassword }).subscribe({
      next: () => {
        this.toast.success('Password changed. Other devices have been signed out.');
        this.form.reset();
        this.submitting.set(false);
      },
      error: (err: unknown) => {
        this.error.set(errorMessage(err));
        this.submitting.set(false);
      },
    });
  }
}
