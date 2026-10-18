import { ChangeDetectionStrategy, Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { errorMessage, toProblem } from '../../../core/http/api-error';
import { ToastService } from '../../../core/notify/toast.service';
import { isoDate } from '../../../shared/date-range';
import { PAYMENT_LABELS, PAYMENT_METHODS, PaymentMethod } from '../../../shared/payment-methods';
import { HasUnsavedChanges } from '../../../shared/unsaved-changes.guard';
import { ExpenseApi } from '../data/expense.api';
import { Expense, ExpenseRequest, Receipt } from '../data/expense.models';
import { bdToday } from '../../../shared/ui/bd-date.pipe';

const MAX_RECEIPT_BYTES = 10 * 1024 * 1024;
const RECEIPT_TYPES = ['image/jpeg', 'image/png', 'image/webp', 'application/pdf'];

/** Add (/expenses/new) and edit (/expenses/:id/edit) form. */
@Component({
  selector: 'app-expense-form-page',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './expense-form-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ExpenseFormPage implements OnInit, HasUnsavedChanges {
  readonly id = input<string>();

  private readonly api = inject(ExpenseApi);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly paymentMethods = PAYMENT_METHODS;
  protected readonly paymentLabels = PAYMENT_LABELS;
  protected readonly today = isoDate(bdToday());

  protected readonly isEdit = computed(() => !!this.id());
  protected readonly loading = signal(false);
  protected readonly saving = signal(false);
  protected readonly uploading = signal(false);
  protected readonly serverError = signal<string | null>(null);
  protected readonly receipt = signal<Receipt | null>(null);
  /** The expense's own category, kept selectable while editing even if it was deactivated. */
  private readonly currentCategoryId = signal<number | null>(null);

  private version: number | undefined;
  private saved = false;

  protected readonly categories = rxResource({ stream: () => this.api.categories() });
  protected readonly categoryOptions = computed(() =>
    (this.categories.hasValue() ? this.categories.value() : []).filter(
      (c) => c.active || c.id === this.currentCategoryId(),
    ),
  );

  protected readonly form = this.fb.group({
    expenseDate: [this.today, Validators.required],
    categoryId: this.fb.control<number | null>(null, Validators.required),
    description: ['', [Validators.required, Validators.maxLength(200)]],
    amount: this.fb.control<number | null>(null, [Validators.required, Validators.min(0.01)]),
    paymentMethod: this.fb.control<PaymentMethod>('CASH', Validators.required),
    reference: ['', Validators.maxLength(80)],
    notes: ['', Validators.maxLength(1000)],
  });

  ngOnInit(): void {
    const id = this.id();
    if (id) {
      this.loading.set(true);
      this.api.get(Number(id)).subscribe({
        next: (expense) => {
          this.patch(expense);
          this.loading.set(false);
        },
        error: (err: unknown) => {
          this.toast.error(errorMessage(err));
          void this.router.navigate(['/expenses']);
        },
      });
    }
  }

  hasUnsavedChanges(): boolean {
    return !this.saved && this.form.dirty;
  }

  protected isPdf(receipt: Receipt): boolean {
    return receipt.contentType === 'application/pdf';
  }

  protected onReceiptSelected(input: HTMLInputElement): void {
    const file = input.files?.[0];
    input.value = '';
    if (!file) {
      return;
    }
    if (!RECEIPT_TYPES.includes(file.type)) {
      this.toast.error('Receipts must be a JPEG, PNG or WebP photo, or a PDF');
      return;
    }
    if (file.size > MAX_RECEIPT_BYTES) {
      this.toast.error('Receipts must be 10 MB or smaller');
      return;
    }
    this.uploading.set(true);
    this.api.uploadReceipt(file).subscribe({
      next: (uploaded) => {
        this.receipt.set({ fileId: uploaded.id, url: uploaded.url, contentType: uploaded.contentType, fileName: file.name });
        this.form.markAsDirty();
        this.uploading.set(false);
      },
      error: (err: unknown) => {
        this.toast.error(errorMessage(err));
        this.uploading.set(false);
      },
    });
  }

  protected removeReceipt(): void {
    this.receipt.set(null);
    this.form.markAsDirty();
  }

  protected submit(): void {
    this.serverError.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const request: ExpenseRequest = {
      expenseDate: v.expenseDate,
      categoryId: v.categoryId!,
      description: v.description.trim(),
      amount: Number(v.amount),
      paymentMethod: v.paymentMethod,
      reference: v.reference.trim() || null,
      notes: v.notes.trim() || null,
      receiptFileId: this.receipt()?.fileId ?? null,
      version: this.version,
    };

    this.saving.set(true);
    const id = this.id();
    const call = id ? this.api.update(Number(id), request) : this.api.create(request);
    call.subscribe({
      next: () => {
        this.saved = true;
        this.toast.success(id ? 'Expense updated' : 'Expense added');
        void this.router.navigate(['/expenses']);
      },
      error: (err: unknown) => {
        this.saving.set(false);
        const problem = toProblem(err);
        const fields = Object.entries(problem.fieldErrors ?? {}).map(([f, m]) => `${f}: ${m}`);
        this.serverError.set(fields.length ? fields.join(' · ') : errorMessage(err));
      },
    });
  }

  private patch(expense: Expense): void {
    this.version = expense.version;
    this.currentCategoryId.set(expense.category.id);
    this.receipt.set(expense.receipt ?? null);
    this.form.setValue({
      expenseDate: expense.expenseDate,
      categoryId: expense.category.id,
      description: expense.description,
      amount: expense.amount,
      paymentMethod: expense.paymentMethod,
      reference: expense.reference ?? '',
      notes: expense.notes ?? '',
    });
    this.form.markAsPristine();
  }
}
