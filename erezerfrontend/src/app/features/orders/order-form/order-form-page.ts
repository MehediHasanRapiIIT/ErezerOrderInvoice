import { ChangeDetectionStrategy, Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import {
  FormArray,
  FormControl,
  FormGroup,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { catchError, debounceTime, distinctUntilChanged, map, of, startWith, switchMap, tap } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { fromBdInputValue, toBdInputValue } from '../../../shared/bd-datetime-input';
import { DateTimePicker } from '../../../shared/ui/date-time-picker';
import { errorMessage, toProblem } from '../../../core/http/api-error';
import { ToastService } from '../../../core/notify/toast.service';
import { COMPLETE_PHONE_DIGITS, phoneKey } from '../../../shared/phone';
import { HasUnsavedChanges } from '../../../shared/unsaved-changes.guard';
import { Customer, CustomerApi } from '../../customers/customer.api';
import { VariantPicker } from '../../stock/variant-picker';
import { SizeField } from '../../stock/size-field';
import { SoldProductsApi } from '../../sold-products/sold-products.api';
import { TakaPipe } from '../../../shared/ui/taka.pipe';
import { OrderApi } from '../data/order.api';
import { PAYMENT_LABELS, PAYMENT_METHODS, PLATFORM_LABELS, PLATFORMS, SIZE_SUGGESTIONS } from '../data/order-labels';
import {
  ExchangeInfo,
  imageDescriptions,
  OrderDetail,
  OrderImage,
  OrderPlatform,
  OrderRequest,
  PaymentMethod,
  StaffOption,
} from '../data/order.models';
import { BdDatePipe } from '../../../shared/ui/bd-date.pipe';

/** The Accepted by list's value for "Other". */
const OTHER_PERSON = -1;
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
  /** The stock variant picked for `color`; null while the typed text is not a picked variant. */
  variantId: FormControl<number | null>;
  /** The variant's size whose stock this item takes; null = not chosen (or no variant). */
  stockItemId: FormControl<number | null>;
  size: FormControl<string>;
  unitPrice: FormControl<number | null>;
  quantity: FormControl<number>;
  images: FormControl<OrderImage[]>;
}>;

let nextKey = 1;

/** Create (/orders/new) and edit (/orders/:id/edit) form. */
@Component({
  selector: 'app-order-form-page',
  imports: [BdDatePipe, ReactiveFormsModule, RouterLink, TakaPipe, VariantPicker, SizeField, DateTimePicker],
  templateUrl: './order-form-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderFormPage implements OnInit, HasUnsavedChanges {
  /** Bound from the :id route parameter; absent when creating. */
  readonly id = input<string>();

  private readonly api = inject(OrderApi);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  private readonly soldProducts = inject(SoldProductsApi);
  /** Names already used, suggested in Product name so the same category is not typed differently. */
  protected readonly productNames = toSignal(this.soldProducts.productNames().pipe(catchError(() => of([] as string[]))), { initialValue: [] as string[] });
  private readonly auth = inject(AuthService);
  /** May choose the order date (admins always). */
  protected readonly canSetDate = this.auth.hasPermission('ORDER_DATE_EDIT');
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly customers = inject(CustomerApi);

  protected readonly paymentMethods = PAYMENT_METHODS;
  protected readonly platforms = PLATFORMS;
  protected readonly otherPerson = OTHER_PERSON;
  protected readonly platformLabels = PLATFORM_LABELS;
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

  /** The person an edited order is credited to, kept selectable if they have since left. */
  private readonly currentAccepted = signal<StaffOption | null>(null);
  private readonly staff = toSignal(this.api.staff().pipe(catchError(() => of([] as StaffOption[]))), {
    initialValue: [] as StaffOption[],
  });
  protected readonly staffOptions = computed(() => {
    const current = this.currentAccepted();
    return current && !this.staff().some((s) => s.id === current.id) ? [current, ...this.staff()] : this.staff();
  });

  private version: number | undefined;
  private saved = false;
  private savedPlatform: OrderPlatform | null = null;

  protected readonly form = this.fb.group({
    orderNumber: ['', [Validators.maxLength(30), Validators.pattern(ORDER_NUMBER_PATTERN)]],
    /** Bangladesh date and time of the order; only users allowed to choose it see the field. */
    orderedAt: [toBdInputValue()],
    paymentMethod: this.fb.control<PaymentMethod>('CASH', Validators.required),
    acceptedBy: this.fb.control<number | null>(null),
    platform: this.fb.control<OrderPlatform | null>(null),
    acceptedByOther: ['', Validators.maxLength(120)],
    platformOther: ['', Validators.maxLength(80)],
    customer: this.fb.group({
      name: ['', [Validators.required, Validators.maxLength(120)]],
      phone: ['', [Validators.required, Validators.pattern(PHONE_PATTERN)]],
      email: ['', [Validators.email, Validators.maxLength(160)]],
      address: ['', [Validators.required, Validators.maxLength(500)]],
    }),
    deliveryCharge: this.fb.control<number | null>(60, [Validators.required, Validators.min(0)]),
    discount: this.fb.control<number | null>(0, Validators.min(0)),
    advance: this.fb.control<number | null>(null, Validators.min(0)),
    items: this.fb.array<ItemForm>([]),
  });

  private readonly formValue = toSignal(this.form.valueChanges.pipe(startWith(this.form.getRawValue())), {
    requireSync: true,
  });

  protected readonly subtotal = computed(() =>
    (this.formValue().items ?? []).reduce((sum, i) => sum + (Number(i?.unitPrice) || 0) * (Number(i?.quantity) || 0), 0),
  );
  /** Set when editing an exchange order: its money works differently (see `total`). */
  protected readonly exchange = signal<ExchangeInfo | null>(null);
  /** An exchange collects the positive price difference (+ delivery when the customer pays it); no discount. */
  protected readonly total = computed(() => {
    const delivery = Number(this.formValue().deliveryCharge) || 0;
    const ex = this.exchange();
    if (ex) {
      return Math.max(this.subtotal() - ex.credit, 0) + (ex.deliveryPaidBy === 'CUSTOMER' ? delivery : 0);
    }
    return this.subtotal() - (Number(this.formValue().discount) || 0) + delivery;
  });
  protected readonly discountTooLarge = computed(() => !this.exchange() && (Number(this.formValue().discount) || 0) > this.subtotal());
  protected readonly advance = computed(() => Math.max(Number(this.formValue().advance) || 0, 0));
  /** Due is always total − advance; typing a due fills in the advance instead. */
  protected readonly due = computed(() => Math.round((this.total() - this.advance()) * 100) / 100);
  protected readonly advanceTooLarge = computed(() => this.advance() > this.total() + 0.001);

  protected setDue(value: string): void {
    const due = Number(value);
    if (value === '' || !Number.isFinite(due)) {
      this.form.controls.advance.setValue(null);
      return;
    }
    const advance = Math.round((this.total() - Math.max(due, 0)) * 100) / 100;
    this.form.controls.advance.setValue(advance > 0 ? advance : null);
    this.form.controls.advance.markAsDirty();
  }
  protected readonly isUploading = computed(() => Object.values(this.uploading()).some((n) => n > 0));

  /** undefined = no complete number yet, null = new customer, otherwise their history. */
  protected readonly returning = signal<Customer | null | undefined>(undefined);
  protected readonly lookingUp = signal(false);
  /** Fields that were empty and got filled from the customer's last order. */
  protected readonly autoFilled = signal<string[]>([]);

  protected readonly differsFromSaved = computed(() => {
    const c = this.returning();
    const v = this.formValue().customer;
    return !!c && !!v && (v.name !== c.name || (v.email ?? '') !== (c.email ?? '') || v.address !== c.address);
  });

  constructor() {
    this.form.controls.acceptedBy.valueChanges.pipe(takeUntilDestroyed()).subscribe((value) => {
      requireText(this.form.controls.acceptedByOther, value === OTHER_PERSON);
    });
    this.form.controls.platform.valueChanges.pipe(takeUntilDestroyed()).subscribe((value) => {
      // An older order can stay "Other" without one.
      requireText(this.form.controls.platformOther, value === 'OTHER' && this.savedPlatform !== 'OTHER');
    });
    // Recognise returning customers as soon as a complete phone number is typed, in any format.
    this.form.controls.customer.controls.phone.valueChanges
      .pipe(
        debounceTime(400),
        map((phone) => phoneKey(phone)),
        distinctUntilChanged(),
        tap((key) => {
          this.autoFilled.set([]);
          if (key.length < COMPLETE_PHONE_DIGITS) {
            this.returning.set(undefined);
          }
        }),
        switchMap((key) => {
          if (key.length < COMPLETE_PHONE_DIGITS) {
            return of(undefined);
          }
          this.lookingUp.set(true);
          return this.customers.lookup(key).pipe(catchError(() => of(undefined))); // a failed lookup never blocks the form
        }),
        takeUntilDestroyed(),
      )
      .subscribe((customer) => {
        this.lookingUp.set(false);
        this.returning.set(customer);
        if (customer && !this.isEdit()) {
          this.fillEmptyFields(customer);
        }
      });
  }

  protected useSavedDetails(): void {
    const c = this.returning();
    if (c) {
      this.form.controls.customer.patchValue({ name: c.name, email: c.email ?? '', address: c.address });
      this.form.markAsDirty();
      this.autoFilled.set([]);
    }
  }

  /** Fills only fields the user has not typed into, so nothing they entered is overwritten. */
  private fillEmptyFields(c: Customer): void {
    const controls = this.form.controls.customer.controls;
    const filled: string[] = [];
    if (!controls.name.value.trim()) {
      controls.name.setValue(c.name);
      filled.push('name');
    }
    if (!controls.email.value.trim() && c.email) {
      controls.email.setValue(c.email);
      filled.push('email');
    }
    if (!controls.address.value.trim()) {
      controls.address.setValue(c.address);
      filled.push('address');
    }
    this.autoFilled.set(filled);
  }

  get items(): FormArray<ItemForm> {
    return this.form.controls.items;
  }

  ngOnInit(): void {
    const id = this.id();
    if (id) {
      this.load(Number(id));
    } else {
      this.addItem();
      // Required on new orders only, so older orders without them can still be edited.
      this.form.controls.acceptedBy.setValidators(Validators.required);
      this.form.controls.platform.setValidators(Validators.required);
      this.form.controls.acceptedBy.setValue(this.auth.user()?.id ?? null);
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
    // A variant must be picked from the list (or left empty) so the order is counted in stock.
    const unpicked = this.items.controls.find((i) => i.controls.color.value.trim() !== '' && i.controls.variantId.value === null);
    if (unpicked) {
      this.toast.error(`${unpicked.controls.productName.value || 'A product'}: choose the variant from the list`);
      return;
    }
    const noSize = this.items.controls.find((i) => i.controls.variantId.value !== null && i.controls.stockItemId.value === null);
    if (noSize) {
      this.toast.error(`${noSize.controls.productName.value || 'A product'}: choose the size`);
      return;
    }
    if (this.form.invalid || this.discountTooLarge() || this.advanceTooLarge() || this.items.length === 0) {
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
    this.soldProducts.refreshNames();
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
    this.exchange.set(order.exchange ?? null);
    this.items.clear();
    order.items.forEach((item) =>
      this.items.push(
        this.createItem({
          productName: item.productName,
          productCode: item.productCode ?? '',
          description: item.description ?? '',
          color: item.color ?? '',
          variantId: item.variantId ?? null,
          stockItemId: item.stockItemId ?? null,
          size: item.size ?? '',
          unitPrice: item.unitPrice,
          quantity: item.quantity,
          images: item.images,
        }),
      ),
    );
    this.form.patchValue({
      orderNumber: order.orderNumber,
      orderedAt: toBdInputValue(order.createdAt),
      paymentMethod: order.paymentMethod,
      acceptedBy: order.acceptedByOther ? OTHER_PERSON : (order.acceptedBy?.id ?? null),
      acceptedByOther: order.acceptedByOther ?? '',
      platform: order.platform ?? null,
      platformOther: order.platformOther ?? '',
      customer: { ...order.customer, email: order.customer.email ?? '' },
      deliveryCharge: order.deliveryCharge,
      discount: order.discount,
      advance: order.advance > 0 ? order.advance : null,
    });
    this.currentAccepted.set(order.acceptedBy ?? null);
    this.savedPlatform = order.platform ?? null;
    requireText(this.form.controls.platformOther, false);
    this.form.markAsPristine();
  }

  protected setImageDescription(item: ItemForm, fileId: number, description: string): void {
    item.controls.images.setValue(item.controls.images.value.map((img) => (img.fileId === fileId ? { ...img, description } : img)));
    item.controls.images.markAsDirty();
  }

  private createItem(value?: Partial<ReturnType<ItemForm['getRawValue']>>): ItemForm {
    return this.fb.group({
      key: [`item-${nextKey++}`],
      productName: [value?.productName ?? '', [Validators.required, Validators.maxLength(160)]],
      productCode: [value?.productCode ?? '', Validators.maxLength(60)],
      description: [value?.description ?? '', Validators.maxLength(1000)],
      color: [value?.color ?? '', Validators.maxLength(80)],
      variantId: this.fb.control<number | null>(value?.variantId ?? null),
      stockItemId: this.fb.control<number | null>(value?.stockItemId ?? null),
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
      // Only a date the user picked; otherwise a new order is dated when saved and an edited one keeps its date.
      orderedAt: this.canSetDate && this.form.controls.orderedAt.dirty ? fromBdInputValue(v.orderedAt) : null,
      paymentMethod: v.paymentMethod,
      acceptedBy: v.acceptedBy === OTHER_PERSON ? null : v.acceptedBy,
      acceptedByOther: v.acceptedBy === OTHER_PERSON ? v.acceptedByOther.trim() : null,
      platform: v.platform,
      platformOther: v.platform === 'OTHER' ? v.platformOther.trim() || null : null,
      customer: {
        name: v.customer.name.trim(),
        phone: v.customer.phone.trim(),
        email: v.customer.email.trim() || null,
        address: v.customer.address.trim(),
      },
      deliveryCharge: Number(v.deliveryCharge) || 0,
      discount: this.exchange() ? 0 : Number(v.discount) || 0,
      advance: Number(v.advance) || 0,
      items: v.items.map((i) => ({
        productName: i.productName.trim(),
        productCode: i.productCode.trim() || null,
        description: i.description.trim() || null,
        color: i.color.trim() || null,
        size: i.size.trim() || null,
        unitPrice: Number(i.unitPrice) || 0,
        quantity: Number(i.quantity) || 1,
        imageIds: i.images.map((img) => img.fileId),
        imageDescriptions: imageDescriptions(i.images),
        variantId: i.variantId,
        stockItemId: i.variantId === null ? null : i.stockItemId,
      })),
      version: this.version,
    };
  }
}

/** Makes a field that only applies to "Other" required while it does. */
function requireText(control: FormControl<string>, required: boolean): void {
  control.setValidators(required ? [Validators.required, Validators.pattern(/\S/), Validators.maxLength(120)] : Validators.maxLength(120));
  control.updateValueAndValidity({ emitEvent: false });
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
