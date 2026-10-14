import { ChangeDetectionStrategy, Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import {
  FormArray,
  FormControl,
  FormGroup,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { errorMessage, toProblem } from '../../../core/http/api-error';
import { ToastService } from '../../../core/notify/toast.service';
import { HasUnsavedChanges } from '../../../shared/unsaved-changes.guard';
import { TakaPipe } from '../../../shared/ui/taka.pipe';
import { OrderApi } from '../data/order.api';
import { PAYMENT_LABELS, PAYMENT_METHODS, SIZE_SUGGESTIONS } from '../data/order-labels';
import { OrderDetail, OrderImage, OrderRequest, PaymentMethod } from '../data/order.models';

const MAX_IMAGES_PER_ITEM = 10;
const MAX_IMAGE_BYTES = 10 * 1024 * 1024;
const ACCEPTED_TYPES = ['image/jpeg', 'image/png', 'image/webp'];
const PHONE_PATTERN = /^[0-9+\-\s]{6,30}$/;
const ORDER_NUMBER_PATTERN = /^[A-Za-z0-9-]*$/;

type ItemForm = FormGroup<{
  key: FormControl<string>;
  productName: FormControl<string>;
  productCode: FormControl<string>;
  description: FormControl<string>;
  color: FormControl<string>;
  size: FormControl<string>;
  unitPrice: FormControl<number | null>;
  quantity: FormControl<number>;
  images: FormControl<OrderImage[]>;
}>;

let nextKey = 1;

/** Create (/orders/new) and edit (/orders/:id/edit) form. */
@Component({
  selector: 'app-order-form-page',
  imports: [ReactiveFormsModule, RouterLink, TakaPipe],
  templateUrl: './order-form-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderFormPage implements OnInit, HasUnsavedChanges {
  /** Bound from the :id route parameter; absent when creating. */
  readonly id = input<string>();

  private readonly api = inject(OrderApi);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  private readonly auth = inject(AuthService);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly paymentMethods = PAYMENT_METHODS;
  protected readonly paymentLabels = PAYMENT_LABELS;
  protected readonly sizeSuggestions = SIZE_SUGGESTIONS;
  protected readonly maxImages = MAX_IMAGES_PER_ITEM;

  protected readonly isEdit = computed(() => !!this.id());
  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly serverErrors = signal<string[]>([]);
  /** Number of uploads in flight per item key. */
  protected readonly uploading = signal<Record<string, number>>({});
  protected readonly dragOverKey = signal<string | null>(null);

  private version: number | undefined;
  private saved = false;

  protected readonly form = this.fb.group({
    orderNumber: ['', [Validators.maxLength(30), Validators.pattern(ORDER_NUMBER_PATTERN)]],
    paymentMethod: this.fb.control<PaymentMethod>('CASH', Validators.required),
    customer: this.fb.group({
      name: ['', [Validators.required, Validators.maxLength(120)]],
      phone: ['', [Validators.required, Validators.pattern(PHONE_PATTERN)]],
      email: ['', [Validators.email, Validators.maxLength(160)]],
      address: ['', [Validators.required, Validators.maxLength(500)]],
    }),
    deliveryCharge: this.fb.control<number | null>(60, [Validators.required, Validators.min(0)]),
    discount: this.fb.control<number | null>(0, Validators.min(0)),
    items: this.fb.array<ItemForm>([]),
  });

  private readonly formValue = toSignal(this.form.valueChanges.pipe(startWith(this.form.getRawValue())), {
    requireSync: true,
  });

  protected readonly subtotal = computed(() =>
    (this.formValue().items ?? []).reduce((sum, i) => sum + (Number(i?.unitPrice) || 0) * (Number(i?.quantity) || 0), 0),
  );
  protected readonly total = computed(
    () => this.subtotal() - (Number(this.formValue().discount) || 0) + (Number(this.formValue().deliveryCharge) || 0),
  );
  protected readonly discountTooLarge = computed(() => (Number(this.formValue().discount) || 0) > this.subtotal());
  protected readonly isUploading = computed(() => Object.values(this.uploading()).some((n) => n > 0));

  get items(): FormArray<ItemForm> {
    return this.form.controls.items;
  }

  ngOnInit(): void {
    const id = this.id();
    if (id) {
      this.load(Number(id));
    } else {
      this.addItem();
    }
  }

  hasUnsavedChanges(): boolean {
    return !this.saved && this.form.dirty;
  }

  protected addItem(): void {
    this.items.push(this.createItem());
    this.form.markAsDirty();
  }

  protected removeItem(index: number): void {
    this.items.removeAt(index);
    this.form.markAsDirty();
  }

  protected placeholders(count: number): number[] {
    return Array.from({ length: count }, (_, i) => i);
  }

  protected uploadingCount(key: string): number {
    return this.uploading()[key] ?? 0;
  }

  protected onFilesSelected(item: ItemForm, input: HTMLInputElement): void {
    if (input.files?.length) {
      this.upload(item, Array.from(input.files));
    }
    input.value = ''; // allow selecting the same file again
  }

  protected onDrop(item: ItemForm, event: DragEvent): void {
    event.preventDefault();
    this.dragOverKey.set(null);
    const files = Array.from(event.dataTransfer?.files ?? []);
    if (files.length) {
      this.upload(item, files);
    }
  }

  protected onDragOver(item: ItemForm, event: DragEvent): void {
    event.preventDefault();
    this.dragOverKey.set(item.controls.key.value);
  }

  protected removeImage(item: ItemForm, fileId: number): void {
    item.controls.images.setValue(item.controls.images.value.filter((img) => img.fileId !== fileId));
    item.controls.images.markAsDirty();
    this.form.markAsDirty();
  }

  protected submit(): void {
    this.serverErrors.set([]);
    if (this.form.invalid || this.discountTooLarge() || this.items.length === 0) {
      this.form.markAllAsTouched();
      this.toast.error(this.items.length === 0 ? 'Add at least one product' : 'Please fix the highlighted fields');
      return;
    }
    if (this.isUploading()) {
      this.toast.info('Wait for the images to finish uploading');
      return;
    }

    const request = this.toRequest();
    this.saving.set(true);
    const id = this.id();
    if (id) {
      this.api.update(Number(id), request).subscribe({
        next: (order) => this.afterSave(order.id, `Order ${order.orderNumber} updated`),
        error: (err: unknown) => this.onSaveError(err),
      });
    } else {
      this.api.create(request).subscribe({
        next: ({ id: newId }) => this.afterSave(newId, 'Order created'),
        error: (err: unknown) => this.onSaveError(err),
      });
    }
  }

  private afterSave(id: number, message: string): void {
    this.saved = true;
    this.saving.set(false);
    this.toast.success(message);
    if (this.auth.hasPermission('ORDER_VIEW')) {
      void this.router.navigate(['/orders', id]);
    } else {
      // Staff who can create but not view orders stay on a fresh form.
      void this.router.navigateByUrl('/', { skipLocationChange: true }).then(() => this.router.navigate(['/orders/new']));
    }
  }

  private onSaveError(err: unknown): void {
    this.saving.set(false);
    const problem = toProblem(err);
    const fieldMessages = Object.entries(problem.fieldErrors ?? {}).map(([field, msg]) => `${humanize(field)}: ${msg}`);
    this.serverErrors.set(fieldMessages.length ? fieldMessages : [errorMessage(err)]);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  private load(id: number): void {
    this.loading.set(true);
    this.api.get(id).subscribe({
      next: (order) => {
        if (!order.editable) {
          this.toast.error('Cancelled or returned orders can no longer be edited');
          void this.router.navigate(['/orders', id]);
          return;
        }
        this.patch(order);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.toast.error(errorMessage(err));
        void this.router.navigate(['/orders']);
      },
    });
  }

  private patch(order: OrderDetail): void {
    this.version = order.version;
    this.items.clear();
    order.items.forEach((item) =>
      this.items.push(
        this.createItem({
          productName: item.productName,
          productCode: item.productCode,
          description: item.description ?? '',
          color: item.color ?? '',
          size: item.size ?? '',
          unitPrice: item.unitPrice,
          quantity: item.quantity,
          images: item.images,
        }),
      ),
    );
    this.form.patchValue({
      orderNumber: order.orderNumber,
      paymentMethod: order.paymentMethod,
      customer: { ...order.customer, email: order.customer.email ?? '' },
      deliveryCharge: order.deliveryCharge,
      discount: order.discount,
    });
    this.form.markAsPristine();
  }

  private createItem(value?: Partial<ReturnType<ItemForm['getRawValue']>>): ItemForm {
    return this.fb.group({
      key: [`item-${nextKey++}`],
      productName: [value?.productName ?? '', [Validators.required, Validators.maxLength(160)]],
      productCode: [value?.productCode ?? '', [Validators.required, Validators.maxLength(60)]],
      description: [value?.description ?? '', Validators.maxLength(1000)],
      color: [value?.color ?? '', Validators.maxLength(60)],
      size: [value?.size ?? '', Validators.maxLength(30)],
      unitPrice: this.fb.control<number | null>(value?.unitPrice ?? null, [Validators.required, Validators.min(0)]),
      quantity: [value?.quantity ?? 1, [Validators.required, Validators.min(1), Validators.max(10000)]],
      images: this.fb.control<OrderImage[]>(value?.images ?? []),
    });
  }

  private upload(item: ItemForm, files: File[]): void {
    const key = item.controls.key.value;
    const room = MAX_IMAGES_PER_ITEM - item.controls.images.value.length - this.uploadingCount(key);
    const valid = files.filter((file) => {
      if (!ACCEPTED_TYPES.includes(file.type)) {
        this.toast.error(`${file.name}: only JPEG, PNG and WebP images are supported`);
        return false;
      }
      if (file.size > MAX_IMAGE_BYTES) {
        this.toast.error(`${file.name}: images must be 10 MB or smaller`);
        return false;
      }
      return true;
    });
    if (valid.length > room) {
      this.toast.error(`A product can have at most ${MAX_IMAGES_PER_ITEM} images`);
    }
    const batch = valid.slice(0, Math.max(room, 0));
    if (!batch.length) {
      return;
    }

    this.setUploading(key, batch.length);
    this.api.uploadImages(batch).subscribe({
      next: (uploaded) => {
        const images = uploaded.map((f) => ({ fileId: f.id, url: f.url }));
        item.controls.images.setValue([...item.controls.images.value, ...images]);
        this.form.markAsDirty();
        this.setUploading(key, -batch.length);
      },
      error: (err: unknown) => {
        this.toast.error(errorMessage(err));
        this.setUploading(key, -batch.length);
      },
    });
  }

  private setUploading(key: string, delta: number): void {
    this.uploading.update((state) => ({ ...state, [key]: Math.max((state[key] ?? 0) + delta, 0) }));
  }

  private toRequest(): OrderRequest {
    const v = this.form.getRawValue();
    return {
      orderNumber: v.orderNumber.trim() || null,
      paymentMethod: v.paymentMethod,
      customer: {
        name: v.customer.name.trim(),
        phone: v.customer.phone.trim(),
        email: v.customer.email.trim() || null,
        address: v.customer.address.trim(),
      },
      deliveryCharge: Number(v.deliveryCharge) || 0,
      discount: Number(v.discount) || 0,
      items: v.items.map((i) => ({
        productName: i.productName.trim(),
        productCode: i.productCode.trim(),
        description: i.description.trim() || null,
        color: i.color.trim() || null,
        size: i.size.trim() || null,
        unitPrice: Number(i.unitPrice) || 0,
        quantity: Number(i.quantity) || 1,
        imageIds: i.images.map((img) => img.fileId),
      })),
      version: this.version,
    };
  }
}

/** "items[0].productCode" → "Product 1 product code" */
function humanize(field: string): string {
  return field
    .replace(/items\[(\d+)]\./, (_, i: string) => `Product ${Number(i) + 1} `)
    .replace(/^customer\./, 'Customer ')
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .toLowerCase()
    .replace(/^\w/, (c) => c.toUpperCase());
}
