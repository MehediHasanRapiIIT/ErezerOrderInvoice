import { ChangeDetectionStrategy, Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { catchError, of } from 'rxjs';
import { errorMessage, toProblem } from '../../../core/http/api-error';
import { AuthService } from '../../../core/auth/auth.service';
import { ToastService } from '../../../core/notify/toast.service';
import { isoDate } from '../../../shared/date-range';
import { PAYMENT_LABELS, PAYMENT_METHODS, PaymentMethod } from '../../../shared/payment-methods';
import { bdToday } from '../../../shared/ui/bd-date.pipe';
import { TakaPipe } from '../../../shared/ui/taka.pipe';
import { HasUnsavedChanges } from '../../../shared/unsaved-changes.guard';
import { AssetApi } from '../data/asset.api';
import { Asset, ASSET_STATUS_LABELS, AssetFile, AssetRequest, AssetStatus, OWNED_STATUSES } from '../data/asset.models';

const MAX_FILE_BYTES = 10 * 1024 * 1024;
const IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp'];

/** Add (/assets/new) and edit (/assets/:id/edit) form. */
@Component({
  selector: 'app-asset-form-page',
  imports: [ReactiveFormsModule, RouterLink, TakaPipe],
  templateUrl: './asset-form-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AssetFormPage implements OnInit, HasUnsavedChanges {
  readonly id = input<string>();

  private readonly api = inject(AssetApi);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly paymentMethods = PAYMENT_METHODS;
  protected readonly paymentLabels = PAYMENT_LABELS;
  protected readonly ownedStatuses = OWNED_STATUSES;
  protected readonly statusLabels = ASSET_STATUS_LABELS;
  protected readonly today = isoDate(bdToday());

  protected readonly isEdit = computed(() => !!this.id());
  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly uploading = signal<'photo' | 'receipt' | null>(null);
  protected readonly serverError = signal<string | null>(null);
  protected readonly photo = signal<AssetFile | null>(null);
  protected readonly receipt = signal<AssetFile | null>(null);
  /** The asset's own category, kept selectable while editing even if it was deactivated. */
  private readonly currentCategoryId = signal<number | null>(null);

  private version: number | undefined;
  private saved = false;

  protected readonly categories = rxResource({ stream: () => this.api.categories() });
  protected readonly categoryOptions = computed(() =>
    (this.categories.hasValue() ? this.categories.value() : []).filter(
      (c) => c.active || c.id === this.currentCategoryId(),
    ),
  );
  protected readonly locations = toSignal(this.api.locations().pipe(catchError(() => of([] as string[]))), {
    initialValue: [] as string[],
  });

  protected readonly canSeeCosts = inject(AuthService).hasPermission('ASSET_COSTS_VIEW');

  protected readonly form = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(120)]],
    categoryId: this.fb.control<number | null>(null, Validators.required),
    quantity: this.fb.control<number | null>(1, [Validators.required, Validators.min(1), Validators.max(100000)]),
    // Only people who can see costs enter (and must enter) the cost.
    unitCost: this.fb.control<number | null>(null, this.canSeeCosts ? [Validators.required, Validators.min(0)] : []),
    purchaseDate: [this.today, Validators.required],
    status: this.fb.control<AssetStatus>('IN_USE'),
    location: ['', Validators.maxLength(80)],
    serialNumber: ['', Validators.maxLength(120)],
    warrantyUntil: [''],
    supplier: ['', Validators.maxLength(120)],
    paymentMethod: this.fb.control<PaymentMethod | ''>(''),
    reference: ['', Validators.maxLength(80)],
    notes: ['', Validators.maxLength(1000)],
  });

  private readonly values = toSignal(this.form.valueChanges, { initialValue: this.form.getRawValue() });
  protected readonly total = computed(() => {
    const v = this.values();
    return Math.round((Number(v.quantity) || 0) * (Number(v.unitCost) || 0) * 100) / 100;
  });

  ngOnInit(): void {
    const id = this.id();
    if (id) {
      this.loading.set(true);
      this.api.get(Number(id)).subscribe({
        next: (asset) => {
          this.patch(asset);
          this.loading.set(false);
        },
        error: (err: unknown) => {
          this.toast.error(errorMessage(err));
          void this.router.navigate(['/assets']);
        },
      });
    }
  }

  hasUnsavedChanges(): boolean {
    return !this.saved && this.form.dirty;
  }

  protected isPdf(file: AssetFile): boolean {
    return file.contentType === 'application/pdf';
  }

  protected onFileSelected(kind: 'photo' | 'receipt', input: HTMLInputElement): void {
    const file = input.files?.[0];
    input.value = '';
    if (!file) {
      return;
    }
    const allowed = kind === 'photo' ? IMAGE_TYPES : [...IMAGE_TYPES, 'application/pdf'];
    if (!allowed.includes(file.type)) {
      this.toast.error(kind === 'photo' ? 'The photo must be a JPEG, PNG or WebP image' : 'The receipt must be a photo or a PDF');
      return;
    }
    if (file.size > MAX_FILE_BYTES) {
      this.toast.error('Files must be 10 MB or smaller');
      return;
    }
    this.uploading.set(kind);
    this.api.upload(file).subscribe({
      next: (uploaded) => {
        const ref: AssetFile = { fileId: uploaded.id, url: uploaded.url, contentType: uploaded.contentType, fileName: file.name };
        (kind === 'photo' ? this.photo : this.receipt).set(ref);
        this.form.markAsDirty();
        this.uploading.set(null);
      },
      error: (err: unknown) => {
        this.toast.error(errorMessage(err));
        this.uploading.set(null);
      },
    });
  }

  protected removeFile(kind: 'photo' | 'receipt'): void {
    (kind === 'photo' ? this.photo : this.receipt).set(null);
    this.form.markAsDirty();
  }

  protected submit(): void {
    this.serverError.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const request: AssetRequest = {
      name: v.name.trim(),
      categoryId: v.categoryId!,
      quantity: Number(v.quantity),
      unitCost: this.canSeeCosts ? Number(v.unitCost) : null,
      purchaseDate: v.purchaseDate,
      supplier: v.supplier.trim() || null,
      paymentMethod: v.paymentMethod || null,
      reference: v.reference.trim() || null,
      location: v.location.trim() || null,
      serialNumber: v.serialNumber.trim() || null,
      warrantyUntil: v.warrantyUntil || null,
      notes: v.notes.trim() || null,
      photoFileId: this.photo()?.fileId ?? null,
      receiptFileId: this.receipt()?.fileId ?? null,
      status: this.isEdit() ? undefined : v.status,
      version: this.version,
    };

    this.saving.set(true);
    const id = this.id();
    const call = id ? this.api.update(Number(id), request) : this.api.create(request);
    call.subscribe({
      next: (asset) => {
        this.saved = true;
        this.toast.success(id ? 'Asset updated' : 'Asset added');
        void this.router.navigate(['/assets', asset.id]);
      },
      error: (err: unknown) => {
        this.saving.set(false);
        const problem = toProblem(err);
        const fields = Object.entries(problem.fieldErrors ?? {}).map(([f, m]) => `${f}: ${m}`);
        this.serverError.set(fields.length ? fields.join(' · ') : errorMessage(err));
      },
    });
  }

  private patch(asset: Asset): void {
    this.version = asset.version;
    this.currentCategoryId.set(asset.category.id);
    this.photo.set(asset.photo ?? null);
    this.receipt.set(asset.receipt ?? null);
    this.form.setValue({
      name: asset.name,
      categoryId: asset.category.id,
      quantity: asset.quantity,
      unitCost: asset.unitCost ?? null,
      purchaseDate: asset.purchaseDate,
      status: asset.status,
      location: asset.location ?? '',
      serialNumber: asset.serialNumber ?? '',
      warrantyUntil: asset.warrantyUntil ?? '',
      supplier: asset.supplier ?? '',
      paymentMethod: asset.paymentMethod ?? '',
      reference: asset.reference ?? '',
      notes: asset.notes ?? '',
    });
    this.form.markAsPristine();
  }
}
