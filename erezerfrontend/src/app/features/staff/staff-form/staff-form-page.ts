import { ChangeDetectionStrategy, Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { startWith } from 'rxjs';
import { Permission, Role } from '../../../core/auth/auth.models';
import { AuthService } from '../../../core/auth/auth.service';
import { errorMessage, toProblem } from '../../../core/http/api-error';
import { ToastService } from '../../../core/notify/toast.service';
import { generatePassword, PASSWORD_PATTERN } from '../../../shared/password';
import { HasUnsavedChanges } from '../../../shared/unsaved-changes.guard';
import { StaffApi } from '../data/staff.api';
import { PermissionInfo, StaffMember } from '../data/staff.models';

const PHONE_PATTERN = /^[0-9+\-\s]{6,30}$/;

/** Add (/staff/new) and edit (/staff/:id/edit) a staff account and its permissions. */
@Component({
  selector: 'app-staff-form-page',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './staff-form-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StaffFormPage implements OnInit, HasUnsavedChanges {
  readonly id = input<string>();

  private readonly api = inject(StaffApi);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  private readonly auth = inject(AuthService);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly isEdit = computed(() => !!this.id());
  protected readonly isSelf = computed(() => !!this.id() && Number(this.id()) === this.auth.user()?.id);
  /** Admins give anything; other staff managers only what they hold themselves, and never the admin role. */
  protected readonly actingAsAdmin = computed(() => this.auth.user()?.role === 'ADMIN');
  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly serverError = signal<string | null>(null);
  protected readonly showPassword = signal(true);
  protected readonly selected = signal<Set<Permission>>(new Set());
  protected readonly member = signal<StaffMember | null>(null);
  private saved = false;
  private permissionsTouched = false;

  protected readonly catalog = rxResource({ stream: () => this.api.permissionCatalog() });

  /** Grantable permissions grouped for the checkbox matrix, in catalog order. */
  protected readonly groups = computed(() => {
    const list: PermissionInfo[] = this.catalog.hasValue() ? this.catalog.value() : [];
    const groups = new Map<string, PermissionInfo[]>();
    list.filter((p) => !p.adminOnly).forEach((p) => groups.set(p.group, [...(groups.get(p.group) ?? []), p]));
    return [...groups].map(([name, permissions]) => ({ name, permissions }));
  });

  protected readonly form = this.fb.group({
    fullName: ['', [Validators.required, Validators.maxLength(120)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(160)]],
    phone: ['', Validators.pattern(PHONE_PATTERN)],
    role: this.fb.control<Role>('MODERATOR', Validators.required),
    password: [generatePassword(), [Validators.required, Validators.pattern(PASSWORD_PATTERN)]],
  });

  protected readonly role = toSignal(this.form.controls.role.valueChanges.pipe(startWith(this.form.controls.role.value)), {
    requireSync: true,
  });

  ngOnInit(): void {
    // A new employee usually gives attendance and adds daily expenses; these can still be unticked.
    this.form.controls.role.valueChanges.subscribe((role) => {
      if (role === 'EMPLOYEE' && !this.isEdit()) {
        const usual = (['ATTENDANCE_MARK', 'DAILY_EXPENSE_CREATE'] as const).filter((p) => this.canGive(p));
        this.selected.update((set) => new Set([...set, ...usual]));
      }
    });
    const id = this.id();
    if (!id) {
      return;
    }
    // Email and password are not edited here (password has its own reset action).
    this.form.controls.email.disable();
    this.form.controls.password.disable();
    this.loading.set(true);
    this.api.get(Number(id)).subscribe({
      next: (member) => {
        this.member.set(member);
        this.form.patchValue({ fullName: member.fullName, email: member.email, phone: member.phone ?? '', role: member.role });
        this.selected.set(new Set(member.role === 'ADMIN' ? [] : member.permissions));
        if (this.isSelf()) {
          this.form.controls.role.disable(); // you cannot change your own role
        }
        this.form.markAsPristine();
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.toast.error(errorMessage(err));
        void this.router.navigate(['/staff']);
      },
    });
  }

  hasUnsavedChanges(): boolean {
    return !this.saved && (this.form.dirty || this.permissionsTouched);
  }

  /** Permissions that come automatically with the ticked ones. */
  private readonly included = computed(() => {
    const list: PermissionInfo[] = this.catalog.hasValue() ? this.catalog.value() : [];
    const result = new Set<Permission>();
    list.filter((p) => this.selected().has(p.name)).forEach((p) => p.includes.forEach((i) => result.add(i)));
    return result;
  });

  protected isChecked(permission: Permission): boolean {
    return this.selected().has(permission) || this.included().has(permission);
  }

  /** Ticked only because another ticked permission includes it. */
  protected isIncluded(permission: Permission): boolean {
    return !this.selected().has(permission) && this.included().has(permission);
  }

  protected canGive(permission: Permission): boolean {
    return this.actingAsAdmin() || this.auth.hasPermission(permission) || this.selected().has(permission);
  }

  protected toggle(permission: Permission): void {
    const next = new Set(this.selected());
    if (next.has(permission)) {
      next.delete(permission);
    } else {
      next.add(permission);
    }
    this.selected.set(next);
    this.permissionsTouched = true;
  }

  protected groupState(group: { permissions: PermissionInfo[] }): 'all' | 'some' | 'none' {
    const count = group.permissions.filter((p) => this.selected().has(p.name)).length;
    return count === 0 ? 'none' : count === group.permissions.length ? 'all' : 'some';
  }

  protected toggleGroup(group: { permissions: PermissionInfo[] }): void {
    const next = new Set(this.selected());
    const selectAll = this.groupState(group) !== 'all';
    group.permissions
      .filter((p) => !selectAll || this.canGive(p.name))
      .forEach((p) => (selectAll ? next.add(p.name) : next.delete(p.name)));
    this.selected.set(next);
    this.permissionsTouched = true;
  }

  protected regeneratePassword(): void {
    this.form.controls.password.setValue(generatePassword());
    this.form.controls.password.markAsDirty();
  }

  protected copyPassword(): void {
    void navigator.clipboard?.writeText(this.form.controls.password.value).then(() => this.toast.info('Password copied'));
  }

  protected submit(): void {
    this.serverError.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const permissions = v.role === 'ADMIN' ? [] : [...this.selected()];
    this.saving.set(true);

    const id = this.id();
    const request = id
      ? this.api.update(Number(id), {
          fullName: v.fullName.trim(),
          phone: v.phone.trim() || null,
          role: v.role,
          permissions,
          version: this.member()!.version,
        })
      : this.api.create({
          fullName: v.fullName.trim(),
          email: v.email.trim(),
          phone: v.phone.trim() || null,
          role: v.role,
          password: v.password,
          permissions,
        });

    request.subscribe({
      next: (member) => {
        this.saved = true;
        this.toast.success(id ? `${member.fullName} updated` : `${member.fullName} added. Share the password with them.`);
        void this.router.navigate(['/staff']);
      },
      error: (err: unknown) => {
        this.saving.set(false);
        const problem = toProblem(err);
        const fields = Object.entries(problem.fieldErrors ?? {}).map(([f, m]) => `${f}: ${m}`);
        this.serverError.set(fields.length ? fields.join(' · ') : errorMessage(err));
      },
    });
  }
}
