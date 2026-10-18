import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Params, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { errorMessage, readBlobError } from '../../../core/http/api-error';
import { fileNameFrom, saveBlob } from '../../../core/http/file-download';
import { ToastService } from '../../../core/notify/toast.service';
import { DATE_RANGES, dateBounds, DateRange } from '../../../shared/date-range';
import { HasPermissionDirective } from '../../../shared/has-permission.directive';
import { PAYMENT_LABELS, PAYMENT_METHODS, PaymentMethod } from '../../../shared/payment-methods';
import { ConfirmService } from '../../../shared/ui/confirm-dialog';
import { Pagination } from '../../../shared/ui/pagination';
import { TakaPipe } from '../../../shared/ui/taka.pipe';
import { ExpenseApi } from '../data/expense.api';
import { Expense, ExpenseQuery } from '../data/expense.models';
import { ExpenseCategoriesDialog } from '../expense-categories/expense-categories-dialog';
import { BdDatePipe } from '../../../shared/ui/bd-date.pipe';

const PAGE_SIZE = 20;
const DEBOUNCE_MS = 350;

/** Expense list. Filter state lives in the URL so reloads and shared links keep it. */
@Component({
  selector: 'app-expense-list-page',
  imports: [BdDatePipe, RouterLink, HasPermissionDirective, Pagination, TakaPipe, ExpenseCategoriesDialog],
  templateUrl: './expense-list-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ExpenseListPage {
  private readonly api = inject(ExpenseApi);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);
  protected readonly auth = inject(AuthService);

  protected readonly dateRanges = [...DATE_RANGES, { value: 'custom' as DateRange, label: 'Custom range' }];
  protected readonly paymentMethods = PAYMENT_METHODS;
  protected readonly paymentLabels = PAYMENT_LABELS;
  protected readonly openMenuId = signal<number | null>(null);
  protected readonly exporting = signal(false);
  protected readonly showCategories = signal(false);

  private readonly params = toSignal(this.route.queryParamMap, { initialValue: this.route.snapshot.queryParamMap });

  protected readonly search = computed(() => this.params().get('q') ?? '');
  protected readonly categoryId = computed(() => this.params().get('category') ?? '');
  protected readonly payment = computed(() => (this.params().get('payment') as PaymentMethod | null) ?? '');
  protected readonly range = computed<DateRange>(() => (this.params().get('range') as DateRange | null) ?? 'all');
  protected readonly customFrom = computed(() => this.params().get('from') ?? '');
  protected readonly customTo = computed(() => this.params().get('to') ?? '');
  protected readonly minAmount = computed(() => this.params().get('min') ?? '');
  protected readonly maxAmount = computed(() => this.params().get('max') ?? '');
  protected readonly page = computed(() => Math.max(0, Number(this.params().get('page') ?? 0) || 0));
  protected readonly hasFilters = computed(
    () =>
      !!this.search() || !!this.categoryId() || !!this.payment() || this.range() !== 'all' ||
      !!this.minAmount() || !!this.maxAmount(),
  );

  private readonly query = computed<ExpenseQuery>(() => {
    const bounds =
      this.range() === 'custom'
        ? { from: this.customFrom() || undefined, to: this.customTo() || undefined }
        : dateBounds(this.range());
    return {
      q: this.search() || undefined,
      categoryId: this.categoryId() ? Number(this.categoryId()) : undefined,
      paymentMethod: this.payment() || undefined,
      ...bounds,
      minAmount: this.minAmount() ? Number(this.minAmount()) : undefined,
      maxAmount: this.maxAmount() ? Number(this.maxAmount()) : undefined,
      page: this.page(),
      size: PAGE_SIZE,
    };
  });

  protected readonly expenses = rxResource({ params: () => this.query(), stream: ({ params }) => this.api.list(params) });
  protected readonly categories = rxResource({ stream: () => this.api.categories() });

  protected readonly result = computed(() => (this.expenses.hasValue() ? this.expenses.value() : undefined));
  protected readonly rows = computed(() => this.result()?.content ?? []);
  protected readonly categoryOptions = computed(() => (this.categories.hasValue() ? this.categories.value() : []));
  protected readonly topCategories = computed(() => {
    const result = this.result();
    if (!result || result.totalAmount <= 0) {
      return [];
    }
    return result.byCategory.slice(0, 5).map((c) => ({ ...c, share: (c.total / result.totalAmount) * 100 }));
  });

  private timer: ReturnType<typeof setTimeout> | undefined;

  constructor() {
    inject(DestroyRef).onDestroy(() => clearTimeout(this.timer));
  }

  protected debounced(key: 'q' | 'min' | 'max', value: string): void {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.updateParams({ [key]: value.trim() || null, page: null }), DEBOUNCE_MS);
  }

  protected setFilter(key: 'category' | 'payment' | 'range' | 'from' | 'to', value: string): void {
    const changes: Params = { [key]: value && value !== 'all' ? value : null, page: null };
    if (key === 'range' && value !== 'custom') {
      changes['from'] = null;
      changes['to'] = null;
    }
    this.updateParams(changes);
  }

  protected clearFilters(): void {
    this.updateParams({ q: null, category: null, payment: null, range: null, from: null, to: null, min: null, max: null, page: null });
  }

  protected goToPage(page: number): void {
    this.updateParams({ page: page > 0 ? page : null });
  }

  protected toggleMenu(id: number, event: Event): void {
    event.stopPropagation();
    this.openMenuId.set(this.openMenuId() === id ? null : id);
  }

  protected open(expense: Expense): void {
    if (this.auth.hasPermission('EXPENSE_EDIT')) {
      void this.router.navigate(['/expenses', expense.id, 'edit']);
    }
  }

  protected exportExcel(): void {
    this.exporting.set(true);
    this.api.exportExcel(this.query()).subscribe({
      next: (response) => {
        saveBlob(response.body!, fileNameFrom(response, 'expenses.xlsx'));
        this.exporting.set(false);
      },
      error: (err: unknown) => {
        void readBlobError(err).then((message) => this.toast.error(message));
        this.exporting.set(false);
      },
    });
  }

  protected async remove(expense: Expense): Promise<void> {
    this.openMenuId.set(null);
    const confirmed = await this.confirm.ask({
      title: 'Delete this expense?',
      message: `“${expense.description}” will be removed from the list and all totals.`,
      confirmLabel: 'Delete expense',
      danger: true,
    });
    if (!confirmed) {
      return;
    }
    this.api.delete(expense.id).subscribe({
      next: () => {
        this.toast.success('Expense deleted');
        this.expenses.reload();
      },
      error: (err: unknown) => this.toast.error(errorMessage(err)),
    });
  }

  protected onCategoriesClosed(changed: boolean): void {
    this.showCategories.set(false);
    if (changed) {
      this.categories.reload();
      this.expenses.reload();
    }
  }

  private updateParams(changes: Params): void {
    void this.router.navigate([], { relativeTo: this.route, queryParams: changes, queryParamsHandling: 'merge' });
  }
}
