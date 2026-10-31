import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { of } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { errorMessage } from '../../core/http/api-error';
import { ToastService } from '../../core/notify/toast.service';
import { BdDatePipe } from '../../shared/ui/bd-date.pipe';
import { ConfirmService } from '../../shared/ui/confirm-dialog';
import { Pagination } from '../../shared/ui/pagination';
import { TakaPipe } from '../../shared/ui/taka.pipe';
import { DailyExpenseCategoriesDialog } from './daily-expense-categories-dialog';
import { DailyExpenseFormDialog } from './daily-expense-form-dialog';
import { DailyExpenseReviewDialog } from './daily-expense-review-dialog';
import { DailyExpenseApi } from './data/daily-expense.api';
import {
  DAILY_STATUS_CLASSES,
  DAILY_STATUS_LABELS,
  DailyExpense,
  DailyExpenseStatus,
  DailyFilter,
  OTHER_CATEGORY,
} from './data/daily-expense.models';

const PAGE_SIZE = 20;
const DEBOUNCE_MS = 350;

/** Daily expenses: employees and moderators apply here, and approvers decide. */
@Component({
  selector: 'app-daily-expense-page',
  imports: [BdDatePipe, DailyExpenseCategoriesDialog, DailyExpenseFormDialog, DailyExpenseReviewDialog, FormsModule, Pagination, TakaPipe],
  template: `
    <div class="flex flex-wrap items-center justify-between gap-4">
      <div>
        <h1 class="page-title">Expense (Daily)</h1>
        <p class="page-subtitle">{{ canViewAll ? 'What employees and moderators spent, for approval.' : 'Add what you spent. An admin approves it.' }}</p>
      </div>
      <div class="flex flex-wrap gap-2">
        @if (canManageCategories) {
          <button type="button" class="btn-secondary" (click)="showCategories.set(true)"><i class="fas fa-tags"></i> Categories</button>
        }
        @if (canAdd) {
          <button type="button" class="btn-primary" (click)="showForm.set(true)"><i class="fas fa-plus"></i> Add Expense</button>
        }
      </div>
    </div>

    <div class="mt-6 grid grid-cols-2 gap-4 lg:grid-cols-4">
      <div class="card p-5">
        <p class="text-sm text-slate-400"><i class="fas fa-wallet mr-1 text-brand-light"></i>Total approved</p>
        <p class="mt-1 text-2xl font-bold text-white">{{ summary()?.approved ?? 0 | taka }}</p>
        <p class="mt-1 text-xs text-slate-500">{{ summary()?.approvedCount ?? 0 }} expense{{ summary()?.approvedCount === 1 ? '' : 's' }}</p>
      </div>
      <div class="card p-5">
        <p class="text-sm text-slate-400"><i class="fas fa-check-circle mr-1 text-emerald-300"></i>Paid</p>
        <p class="mt-1 text-2xl font-bold text-white">{{ summary()?.paid ?? 0 | taka }}</p>
        <p class="mt-1 text-xs text-slate-500">of the approved total</p>
      </div>
      <div class="card p-5">
        <p class="text-sm text-slate-400"><i class="fas fa-hourglass-half mr-1 text-rose-300"></i>Due</p>
        <p class="mt-1 text-2xl font-bold text-white">{{ summary()?.due ?? 0 | taka }}</p>
        <p class="mt-1 text-xs text-slate-500">still to pay</p>
      </div>
      <div class="card p-5">
        <p class="text-sm text-slate-400"><i class="fas fa-clock mr-1 text-amber-300"></i>Waiting for approval</p>
        <p class="mt-1 text-2xl font-bold text-white">{{ summary()?.pending ?? 0 | taka }}</p>
        <p class="mt-1 text-xs text-slate-500">{{ summary()?.pendingCount ?? 0 }} expense{{ summary()?.pendingCount === 1 ? '' : 's' }}</p>
      </div>
    </div>

    @if (canApprove) {
      <div class="mt-6 flex flex-wrap gap-2" role="tablist">
        <button type="button" role="tab" [attr.aria-selected]="tab() === 'list'" [class]="tab() === 'list' ? 'btn-primary' : 'btn-secondary'" (click)="tab.set('list')">
          <i class="fas fa-list"></i> Expenses
        </button>
        <button type="button" role="tab" [attr.aria-selected]="tab() === 'approvals'" [class]="tab() === 'approvals' ? 'btn-primary' : 'btn-secondary'" (click)="tab.set('approvals')">
          <i class="fas fa-check-double"></i> Approvals ({{ summary()?.pendingCount ?? 0 }})
        </button>
      </div>
    }

    <section class="card mt-4 overflow-hidden">
      <div class="grid gap-2 border-b border-line p-4 sm:grid-cols-2 lg:grid-cols-7">
        <input type="search" class="field lg:col-span-2" placeholder="Search category or details…" [value]="search()" (input)="debounced($any($event.target).value)" aria-label="Search" />
        @if (tab() === 'list') {
          <select class="field" aria-label="Status" [ngModel]="status()" (ngModelChange)="status.set($event)">
            <option [ngValue]="null">All statuses</option>
            @for (s of statuses; track s) { <option [ngValue]="s">{{ statusLabels[s] }}</option> }
          </select>
        }
        <select class="field" aria-label="Category" [ngModel]="categoryId()" (ngModelChange)="categoryId.set($event)">
          <option [ngValue]="null">All categories</option>
          @for (c of categories(); track c.id) { <option [ngValue]="c.id">{{ c.name }}</option> }
          <option [ngValue]="otherValue">Other</option>
        </select>
        @if (canViewAll) {
          <select class="field" aria-label="Employee" [ngModel]="userId()" (ngModelChange)="userId.set($event)">
            <option [ngValue]="null">All employees</option>
            @for (p of people(); track p.id) { <option [ngValue]="p.id">{{ p.name }}</option> }
          </select>
        }
        <input type="date" class="field" aria-label="From date" [max]="to() || null" [ngModel]="from()" (ngModelChange)="from.set($event || '')" />
        <input type="date" class="field" aria-label="To date" [min]="from() || null" [ngModel]="to()" (ngModelChange)="to.set($event || '')" />
      </div>

      <div class="overflow-x-auto">
        <table class="phone-cards w-full min-w-[860px] text-left text-sm">
          <thead class="border-b border-line text-slate-400">
            <tr>
              <th class="px-4 py-3 font-semibold">Category</th>
              <th class="px-4 py-3 font-semibold">Date</th>
              @if (canViewAll) { <th class="px-4 py-3 font-semibold">Employee</th> }
              <th class="px-4 py-3 text-right font-semibold">Amount</th>
              <th class="px-4 py-3 text-right font-semibold">Paid</th>
              <th class="px-4 py-3 text-right font-semibold">Due</th>
              <th class="px-4 py-3 font-semibold">Status</th>
              <th class="px-4 py-3"><span class="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            @for (e of rows(); track e.id) {
              <tr class="border-b border-line/60">
                <td class="py-3 pl-4 pr-28 md:pr-4">
                  <span class="font-medium text-white">{{ e.category }}</span>
                  @if (e.other) { <span class="ml-1 text-xs text-slate-500">(Other)</span> }
                  @if (e.details) { <span class="block max-w-xs truncate text-xs text-slate-400">{{ e.details }}</span> }
                </td>
                <td class="cell-inline whitespace-nowrap px-4 py-3 text-slate-200" data-label="Date">{{ e.date | bdDate: 'd MMM y' }}</td>
                @if (canViewAll) { <td class="cell-inline px-4 py-3 text-slate-200" data-label="Employee">{{ e.requestedBy.name }}</td> }
                <td class="cell-inline whitespace-nowrap px-4 py-3 text-right font-semibold text-white" data-label="Amount">{{ e.amount | taka }}</td>
                <td class="cell-inline whitespace-nowrap px-4 py-3 text-right text-slate-200" data-label="Paid">{{ e.status === 'APPROVED' ? (e.paid | taka) : '–' }}</td>
                <td class="cell-inline whitespace-nowrap px-4 py-3 text-right text-slate-200" data-label="Due">{{ e.status === 'APPROVED' ? (e.due ?? 0 | taka) : '–' }}</td>
                <td class="cell-inline px-4 py-3" data-label="Status">
                  <span class="whitespace-nowrap rounded-full border px-2 py-0.5 text-xs" [class]="statusClasses[e.status]">{{ statusLabels[e.status] }}</span>
                </td>
                <td class="cell-actions px-4 py-3 text-right">
                  <div class="flex justify-end gap-2">
                    @if (canEdit && e.requestedBy.id !== myId) {
                      <button type="button" class="btn-secondary" [attr.aria-label]="'Edit ' + e.category" title="Edit" (click)="editing.set(e)"><i class="fas fa-pen"></i></button>
                    }
                    @if (e.requestedBy.id === myId ? e.status === 'PENDING' : canDelete) {
                      <button type="button" class="btn-secondary" [attr.aria-label]="(e.requestedBy.id === myId ? 'Take back ' : 'Delete ') + e.category" [title]="e.requestedBy.id === myId ? 'Take back' : 'Delete'" (click)="cancel(e)"><i class="fas fa-trash"></i></button>
                    }
                    <button type="button" class="btn-secondary" (click)="viewing.set(e)">{{ tab() === 'approvals' ? 'Review' : 'View' }}</button>
                  </div>
                </td>
              </tr>
            } @empty {
              <tr><td [attr.colspan]="canViewAll ? 8 : 7" class="px-4 py-10 text-center text-slate-500">
                @if (list.isLoading()) { Loading… } @else if (list.error()) { Couldn't load the expenses. } @else if (tab() === 'approvals') { Nothing is waiting for approval. } @else { No expenses found. }
              </td></tr>
            }
          </tbody>
        </table>
      </div>

      @if (result(); as r) {
        @if (r.totalElements > 0) {
          <div class="border-t border-line px-4 py-3">
            <app-pagination [page]="r.page" [size]="r.size" [totalElements]="r.totalElements" [totalPages]="r.totalPages" (pageChange)="page.set($event)" />
          </div>
        }
      }
    </section>

    @if (showForm()) {
      <app-daily-expense-form-dialog [categories]="categories()" (closed)="formClosed($event)" />
    }
    @if (editing(); as e) {
      <app-daily-expense-form-dialog [categories]="categories()" [expense]="e" (closed)="editClosed($event)" />
    }
    @if (viewing(); as e) {
      <app-daily-expense-review-dialog [expense]="e" (closed)="reviewClosed($event)" />
    }
    @if (showCategories()) {
      <app-daily-expense-categories-dialog (closed)="categoriesClosed($event)" />
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DailyExpensePage {
  private readonly api = inject(DailyExpenseApi);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);

  protected readonly canViewAll = this.auth.hasPermission('DAILY_EXPENSE_VIEW');
  protected readonly canApprove = this.auth.hasPermission('DAILY_EXPENSE_APPROVE');
  protected readonly canManageCategories = this.auth.hasPermission('DAILY_EXPENSE_CATEGORIES');
  protected readonly canEdit = this.auth.hasPermission('DAILY_EXPENSE_EDIT');
  protected readonly canDelete = this.auth.hasPermission('DAILY_EXPENSE_DELETE');
  /** Admins decide; the expenses themselves come from employees and moderators. */
  protected readonly canAdd = this.auth.hasPermission('DAILY_EXPENSE_CREATE') && this.auth.user()?.role !== 'ADMIN';
  protected readonly myId = this.auth.user()?.id;

  protected readonly statuses: readonly DailyExpenseStatus[] = ['APPROVED', 'PENDING', 'REJECTED'];
  protected readonly statusLabels = DAILY_STATUS_LABELS;
  protected readonly statusClasses = DAILY_STATUS_CLASSES;
  protected readonly otherValue = OTHER_CATEGORY;

  protected readonly tab = signal<'list' | 'approvals'>('list');
  protected readonly search = signal('');
  /** Approvers start on what was approved, and everyone else on all of their own. */
  protected readonly status = signal<DailyExpenseStatus | null>(this.canViewAll ? 'APPROVED' : null);
  protected readonly categoryId = signal<number | null>(null);
  protected readonly userId = signal<number | null>(null);
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly showForm = signal(false);
  protected readonly showCategories = signal(false);
  protected readonly viewing = signal<DailyExpense | null>(null);
  protected readonly editing = signal<DailyExpense | null>(null);

  private readonly filter = computed<DailyFilter>(() => ({
    status: this.tab() === 'approvals' ? 'PENDING' : this.status(),
    categoryId: this.categoryId(),
    userId: this.userId(),
    from: this.from(),
    to: this.to(),
    q: this.search(),
  }));
  protected readonly page = linkedSignal({ source: this.filter, computation: () => 0 });

  protected readonly list = rxResource({
    params: () => ({ filter: this.filter(), page: this.page() }),
    stream: ({ params }) => this.api.list(params.filter, params.page, PAGE_SIZE),
  });
  private readonly totals = rxResource({ params: () => this.filter(), stream: ({ params }) => this.api.summary(params) });
  private readonly categoryList = rxResource({ stream: () => this.api.categories() });
  private readonly peopleList = rxResource({ stream: () => (this.canViewAll ? this.api.people() : of([])) });

  protected readonly result = computed(() => (this.list.hasValue() ? this.list.value() : undefined));
  protected readonly rows = computed(() => this.result()?.content ?? []);
  protected readonly summary = computed(() => (this.totals.hasValue() ? this.totals.value() : undefined));
  protected readonly categories = computed(() => (this.categoryList.hasValue() ? this.categoryList.value() : []));
  protected readonly people = computed(() => (this.peopleList.hasValue() ? this.peopleList.value() : []));

  private timer: ReturnType<typeof setTimeout> | undefined;

  constructor() {
    inject(DestroyRef).onDestroy(() => clearTimeout(this.timer));
  }

  protected debounced(value: string): void {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.search.set(value.trim()), DEBOUNCE_MS);
  }

  private refresh(): void {
    const r = this.result();
    if (r && r.page > 0 && r.content.length === 1) {
      this.page.set(r.page - 1);
    } else {
      this.list.reload();
    }
    this.totals.reload();
    this.peopleList.reload();
  }

  protected formClosed(saved: boolean): void {
    this.showForm.set(false);
    if (saved) {
      this.refresh();
    }
  }

  protected editClosed(saved: boolean): void {
    this.editing.set(null);
    if (saved) {
      this.refresh();
    }
  }

  protected reviewClosed(saved: boolean): void {
    this.viewing.set(null);
    if (saved) {
      this.refresh();
    }
  }

  protected categoriesClosed(changed: boolean): void {
    this.showCategories.set(false);
    if (changed) {
      this.categoryList.reload();
    }
  }

  protected async cancel(expense: DailyExpense): Promise<void> {
    const mine = expense.requestedBy.id === this.myId;
    const confirmed = await this.confirm.ask({
      title: mine ? 'Take back this expense?' : `Delete ${expense.requestedBy.name}'s expense?`,
      message: mine ? 'It will be removed and the admin will no longer see it.' : 'It is removed permanently and leaves the totals.',
      danger: true,
    });
    if (!confirmed) return;
    this.api.cancel(expense.id).subscribe({
      next: () => {
        this.toast.success(mine ? 'Expense taken back' : 'Expense deleted');
        this.refresh();
      },
      error: (err: unknown) => this.toast.error(errorMessage(err)),
    });
  }
}
