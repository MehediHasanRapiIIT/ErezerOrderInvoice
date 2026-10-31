import { ChangeDetectionStrategy, Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { errorMessage } from '../../core/http/api-error';
import { ToastService } from '../../core/notify/toast.service';
import { HasUnsavedChanges } from '../../shared/unsaved-changes.guard';
import { EmployeeApi } from './data/employee.api';
import { ACCOUNT_TYPE_LABELS, ACCOUNT_TYPES, AccountType, Employee, EmployeeFile, EmployeeRequest, SalaryAccount } from './data/employee.models';

const MAX_FILE_BYTES = 10 * 1024 * 1024;
const IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp'];
const PHONE = /^[0-9+\-\s]{6,30}$/;
const NID = /^(\d{10}|\d{13}|\d{17})$/;
const MAX_ACCOUNTS = 10;

type ImageKind = 'photo' | 'nid' | 'idCard';

const IMAGE_LABELS: Record<ImageKind, string> = {
  photo: 'Employee image *',
  nid: 'NID image *',
  idCard: 'Employee ID card image',
};

/** Register (/employees/new) and edit (/employees/:id/edit) form. */
@Component({
  selector: 'app-employee-form-page',
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <div class="mx-auto max-w-3xl">
      <a [routerLink]="isEdit() ? ['/employees', id()] : ['/employees']" class="mb-2 inline-flex items-center gap-1 text-sm text-slate-400 hover:text-white">
        <i class="fas fa-arrow-left text-xs"></i> {{ isEdit() ? 'Employee' : 'Employees' }}
      </a>
      <h1 class="page-title">{{ isEdit() ? 'Edit Employee' : 'Register Employee' }}</h1>
      <p class="page-subtitle">Fields marked * are required.</p>

      @if (loading()) {
        <div class="flex justify-center py-24 text-slate-400"><i class="fas fa-circle-notch fa-spin text-2xl"></i></div>
      } @else {
        @if (serverError(); as message) {
          <div class="mt-4 rounded-lg border border-red-800 bg-red-950/60 px-4 py-3 text-sm text-red-200" role="alert">{{ message }}</div>
        }

        <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
          <section class="card mt-6 grid grid-cols-1 gap-5 p-6 sm:grid-cols-2">
            <label class="block">
              <span class="field-label">Name *</span>
              <input formControlName="fullName" class="field" maxlength="120" [class.field-invalid]="bad('fullName')" />
              @if (bad('fullName')) { <span class="field-error">Enter the name</span> }
            </label>
            <label class="block">
              <span class="field-label">Employee ID *</span>
              <input formControlName="employeeCode" class="field" maxlength="40" [class.field-invalid]="bad('employeeCode')" />
              @if (bad('employeeCode')) { <span class="field-error">Enter the Employee ID</span> }
            </label>
            <label class="block">
              <span class="field-label">Contact number *</span>
              <input formControlName="phone" type="tel" class="field" maxlength="30" [class.field-invalid]="bad('phone')" />
              @if (bad('phone')) { <span class="field-error">Enter a valid contact number</span> }
            </label>
            <label class="block">
              <span class="field-label">NID number *</span>
              <input formControlName="nidNumber" inputmode="numeric" class="field" maxlength="17" [class.field-invalid]="bad('nidNumber')" />
              @if (bad('nidNumber')) { <span class="field-error">Enter 10, 13 or 17 digits</span> }
            </label>
            <label class="block sm:col-span-2">
              <span class="field-label">Present address *</span>
              <textarea formControlName="presentAddress" rows="2" class="field" maxlength="500" [class.field-invalid]="bad('presentAddress')"></textarea>
              @if (bad('presentAddress')) { <span class="field-error">Enter the present address</span> }
            </label>
            <label class="block sm:col-span-2">
              <span class="field-label">Permanent address</span>
              <textarea formControlName="permanentAddress" rows="2" class="field" maxlength="500"></textarea>
            </label>
          </section>

          <section class="card mt-4 grid grid-cols-1 gap-5 p-6 sm:grid-cols-2">
            <h2 class="font-semibold text-white sm:col-span-2">Family</h2>
            <label class="block">
              <span class="field-label">Father's name</span>
              <input formControlName="fatherName" class="field" maxlength="120" />
            </label>
            <label class="block">
              <span class="field-label">Father's contact number</span>
              <input formControlName="fatherPhone" type="tel" class="field" maxlength="30" [class.field-invalid]="bad('fatherPhone')" />
              @if (bad('fatherPhone')) { <span class="field-error">Enter a valid contact number</span> }
            </label>
            <label class="block">
              <span class="field-label">Mother's name</span>
              <input formControlName="motherName" class="field" maxlength="120" />
            </label>
            <label class="block">
              <span class="field-label">Mother's contact number</span>
              <input formControlName="motherPhone" type="tel" class="field" maxlength="30" [class.field-invalid]="bad('motherPhone')" />
              @if (bad('motherPhone')) { <span class="field-error">Enter a valid contact number</span> }
            </label>
          </section>

          <section class="card mt-4 grid grid-cols-1 gap-5 p-6 sm:grid-cols-2">
            <h2 class="font-semibold text-white sm:col-span-2">Documents</h2>
            @for (kind of imageKinds; track kind) {
              @let file = images[kind]();
              <div>
                <span class="field-label">{{ imageLabels[kind] }}</span>
                @if (file) {
                  <div class="flex items-center gap-3 rounded-lg border border-line p-2">
                    <a [href]="file.url" target="_blank" rel="noopener"><img [src]="file.url" [alt]="imageLabels[kind]" class="h-16 w-24 rounded object-cover" /></a>
                    <span class="min-w-0 flex-1 text-xs text-slate-400">Image added</span>
                    <button type="button" class="cursor-pointer rounded p-2 text-red-300 hover:bg-red-500/10" (click)="removeImage(kind)" [attr.aria-label]="'Remove ' + imageLabels[kind]"><i class="fas fa-times"></i></button>
                  </div>
                } @else {
                  <label class="flex cursor-pointer items-center gap-3 rounded-lg border border-dashed p-4 text-slate-400 hover:text-white" [class]="missing()[kind] ? 'border-red-500' : 'border-line'">
                    <i class="fas text-2xl" [class.fa-id-card]="uploading() !== kind" [class.fa-circle-notch]="uploading() === kind" [class.fa-spin]="uploading() === kind"></i>
                    <span class="text-sm">{{ uploading() === kind ? 'Uploading…' : 'Add an image' }}
                      <span class="block text-xs text-slate-500">JPEG, PNG or WebP · 10 MB</span></span>
                    <input type="file" class="sr-only" accept="image/jpeg,image/png,image/webp" [disabled]="uploading() !== null" (change)="onImage(kind, $any($event.target))" />
                  </label>
                  @if (missing()[kind]) { <span class="field-error">Add this image</span> }
                }
              </div>
            }
          </section>

          <section class="card mt-4 p-6" formArrayName="salaryAccounts">
            <div class="flex flex-wrap items-center justify-between gap-3">
              <h2 class="font-semibold text-white">Salary accounts</h2>
              <button type="button" class="btn-secondary" [disabled]="accounts.length >= maxAccounts" (click)="addAccount()"><i class="fas fa-plus"></i> Add account</button>
            </div>
            @for (group of accounts.controls; track group; let i = $index) {
              <div class="mt-4 grid grid-cols-1 gap-3 rounded-lg border border-line p-4 sm:grid-cols-2" [formGroupName]="i">
                <label class="block">
                  <span class="field-label">Type *</span>
                  <select formControlName="type" class="field">
                    @for (t of accountTypes; track t) { <option [ngValue]="t">{{ typeLabels[t] }}</option> }
                  </select>
                </label>
                @if (group.controls.type.value === 'BANK' || group.controls.type.value === 'OTHER') {
                  <label class="block">
                    <span class="field-label">{{ group.controls.type.value === 'BANK' ? 'Bank name *' : 'What kind of account *' }}</span>
                    <input formControlName="provider" class="field" maxlength="80" [class.field-invalid]="accountBad(i, 'provider')" />
                    @if (accountBad(i, 'provider')) { <span class="field-error">Required</span> }
                  </label>
                }
                <label class="block">
                  <span class="field-label">Account number *</span>
                  <input formControlName="accountNumber" class="field" maxlength="60" [class.field-invalid]="accountBad(i, 'accountNumber')" />
                  @if (accountBad(i, 'accountNumber')) { <span class="field-error">Enter the account number</span> }
                </label>
                <label class="block">
                  <span class="field-label">Account name</span>
                  <input formControlName="accountName" class="field" maxlength="120" />
                </label>
                @if (group.controls.type.value === 'BANK') {
                  <label class="block">
                    <span class="field-label">Branch name</span>
                    <input formControlName="branch" class="field" maxlength="120" />
                  </label>
                  <label class="block">
                    <span class="field-label">Routing number</span>
                    <input formControlName="routingNumber" class="field" maxlength="30" />
                  </label>
                }
                <div class="sm:col-span-2">
                  <button type="button" class="text-sm text-red-300 hover:underline" (click)="removeAccount(i)">Remove this account</button>
                </div>
              </div>
            } @empty {
              <p class="mt-3 text-sm text-slate-500">No account added yet.</p>
            }
          </section>

          <div class="mt-6 flex justify-end gap-2">
            <a [routerLink]="isEdit() ? ['/employees', id()] : ['/employees']" class="btn-secondary">Cancel</a>
            <button type="submit" class="btn-primary" [disabled]="saving() || uploading() !== null">
              {{ saving() ? 'Saving…' : isEdit() ? 'Save changes' : 'Register Employee' }}
            </button>
          </div>
        </form>
      }
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EmployeeFormPage implements OnInit, HasUnsavedChanges {
  readonly id = input<string>();

  private readonly api = inject(EmployeeApi);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly accountTypes = ACCOUNT_TYPES;
  protected readonly typeLabels = ACCOUNT_TYPE_LABELS;
  protected readonly maxAccounts = MAX_ACCOUNTS;
  protected readonly imageKinds: readonly ImageKind[] = ['photo', 'nid', 'idCard'];
  protected readonly imageLabels = IMAGE_LABELS;

  protected readonly isEdit = computed(() => !!this.id());
  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly uploading = signal<ImageKind | null>(null);
  protected readonly serverError = signal<string | null>(null);
  protected readonly images = {
    photo: signal<EmployeeFile | null>(null),
    nid: signal<EmployeeFile | null>(null),
    idCard: signal<EmployeeFile | null>(null),
  };
  /** Required images left empty at the last save. */
  protected readonly missing = signal<Partial<Record<ImageKind, boolean>>>({});

  private version: number | undefined;
  private saved = false;

  protected readonly form = this.fb.group({
    fullName: ['', [Validators.required, Validators.pattern(/\S/)]],
    employeeCode: ['', [Validators.required, Validators.pattern(/\S/)]],
    phone: ['', [Validators.required, Validators.pattern(PHONE)]],
    nidNumber: ['', [Validators.required, Validators.pattern(NID)]],
    presentAddress: ['', [Validators.required, Validators.pattern(/\S/)]],
    permanentAddress: [''],
    fatherName: [''],
    fatherPhone: ['', Validators.pattern(PHONE)],
    motherName: [''],
    motherPhone: ['', Validators.pattern(PHONE)],
    salaryAccounts: this.fb.array<ReturnType<EmployeeFormPage['accountGroup']>>([]),
  });

  protected get accounts() {
    return this.form.controls.salaryAccounts;
  }

  ngOnInit(): void {
    const id = this.id();
    if (!id) return;
    this.loading.set(true);
    this.api.get(Number(id)).subscribe({
      next: (employee) => {
        this.fill(employee);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.serverError.set(errorMessage(err));
        this.loading.set(false);
      },
    });
  }

  hasUnsavedChanges(): boolean {
    return this.form.dirty && !this.saved;
  }

  protected bad(name: 'fullName' | 'employeeCode' | 'phone' | 'nidNumber' | 'presentAddress' | 'fatherPhone' | 'motherPhone'): boolean {
    const control = this.form.controls[name];
    return control.invalid && control.touched;
  }

  protected accountBad(index: number, name: 'provider' | 'accountNumber'): boolean {
    const group = this.accounts.at(index);
    const type = group.controls.type.value;
    if (name === 'provider') {
      return group.controls.provider.touched && needsProvider(type) && !group.controls.provider.value.trim();
    }
    return group.controls.accountNumber.touched && group.controls.accountNumber.invalid;
  }

  private accountGroup(account?: SalaryAccount) {
    return this.fb.group({
      type: this.fb.control<AccountType>(account?.type ?? 'BANK'),
      provider: [account?.provider ?? ''],
      accountName: [account?.accountName ?? ''],
      branch: [account?.branch ?? ''],
      routingNumber: [account?.routingNumber ?? ''],
      accountNumber: [account?.accountNumber ?? '', [Validators.required, Validators.pattern(/\S/)]],
    });
  }

  protected addAccount(): void {
    this.accounts.push(this.accountGroup());
    this.form.markAsDirty();
  }

  protected removeAccount(index: number): void {
    this.accounts.removeAt(index);
    this.form.markAsDirty();
  }

  protected onImage(kind: ImageKind, element: HTMLInputElement): void {
    const file = element.files?.[0];
    element.value = '';
    if (!file) return;
    if (!IMAGE_TYPES.includes(file.type)) {
      this.toast.error('The image must be a JPEG, PNG or WebP file');
      return;
    }
    if (file.size > MAX_FILE_BYTES) {
      this.toast.error('The image must be 10 MB or smaller');
      return;
    }
    this.uploading.set(kind);
    this.api.uploadImage(file).subscribe({
      next: (uploaded) => {
        this.images[kind].set({ fileId: uploaded.id, url: uploaded.url });
        this.missing.update((m) => ({ ...m, [kind]: false }));
        this.form.markAsDirty();
        this.uploading.set(null);
      },
      error: (err: unknown) => {
        this.toast.error(errorMessage(err));
        this.uploading.set(null);
      },
    });
  }

  protected removeImage(kind: ImageKind): void {
    this.images[kind].set(null);
    this.form.markAsDirty();
  }

  protected submit(): void {
    this.serverError.set(null);
    const providersMissing = this.accounts.controls.some((g) => needsProvider(g.controls.type.value) && !g.controls.provider.value.trim());
    const photo = this.images.photo();
    const nidImage = this.images.nid();
    this.missing.set({ photo: !photo, nid: !nidImage });
    if (this.form.invalid || !photo || !nidImage || providersMissing) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const request: EmployeeRequest = {
      employeeCode: v.employeeCode.trim(),
      fullName: v.fullName.trim(),
      phone: v.phone.trim(),
      fatherName: blankToNull(v.fatherName),
      fatherPhone: blankToNull(v.fatherPhone),
      motherName: blankToNull(v.motherName),
      motherPhone: blankToNull(v.motherPhone),
      presentAddress: v.presentAddress.trim(),
      permanentAddress: blankToNull(v.permanentAddress),
      nidNumber: v.nidNumber.trim(),
      photoFileId: photo.fileId,
      nidImageFileId: nidImage.fileId,
      idCardImageFileId: this.images.idCard()?.fileId ?? null,
      salaryAccounts: v.salaryAccounts.map((a) => ({
        type: a.type,
        provider: needsProvider(a.type) ? a.provider.trim() : null,
        accountName: blankToNull(a.accountName),
        accountNumber: a.accountNumber.trim(),
        branch: a.type === 'BANK' ? blankToNull(a.branch) : null,
        routingNumber: a.type === 'BANK' ? blankToNull(a.routingNumber) : null,
      })),
      version: this.version,
    };
    const id = this.id();
    this.saving.set(true);
    (id ? this.api.update(Number(id), request) : this.api.create(request)).subscribe({
      next: (employee) => {
        this.saved = true;
        this.toast.success(id ? 'Changes saved' : 'Employee registered');
        void this.router.navigate(['/employees', employee.id]);
      },
      error: (err: unknown) => {
        this.serverError.set(errorMessage(err));
        this.saving.set(false);
        window.scrollTo({ top: 0, behavior: 'smooth' });
      },
    });
  }

  private fill(e: Employee): void {
    this.version = e.version;
    this.form.patchValue({
      fullName: e.fullName,
      employeeCode: e.employeeCode,
      phone: e.phone,
      nidNumber: e.nidNumber,
      presentAddress: e.presentAddress,
      permanentAddress: e.permanentAddress ?? '',
      fatherName: e.fatherName ?? '',
      fatherPhone: e.fatherPhone ?? '',
      motherName: e.motherName ?? '',
      motherPhone: e.motherPhone ?? '',
    });
    e.salaryAccounts.forEach((a) => this.accounts.push(this.accountGroup(a)));
    this.images.photo.set(e.photo);
    this.images.nid.set(e.nidImage);
    this.images.idCard.set(e.idCardImage ?? null);
    this.form.markAsPristine();
  }
}

function needsProvider(type: AccountType): boolean {
  return type === 'BANK' || type === 'OTHER';
}

function blankToNull(value: string): string | null {
  return value.trim() || null;
}
