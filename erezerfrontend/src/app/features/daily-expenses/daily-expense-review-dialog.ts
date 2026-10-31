import { ChangeDetectionStrategy, Component, computed, inject, input, OnInit, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { errorMessage } from '../../core/http/api-error';
import { ToastService } from '../../core/notify/toast.service';
import { BdDatePipe } from '../../shared/ui/bd-date.pipe';
import { TakaPipe } from '../../shared/ui/taka.pipe';
import { DailyExpenseApi } from './data/daily-expense.api';
import { DAILY_STATUS_CLASSES, DAILY_STATUS_LABELS, DailyExpense } from './data/daily-expense.models';

/** All of an expense's details. An approver enters what was paid and approves, or rejects with a reason. */
@Component({
  selector: 'app-daily-expense-review-dialog',
  imports: [BdDatePipe, FormsModule, TakaPipe],
  template: `
    <div class="fixed inset-0 z-[85] flex items-start justify-center overflow-y-auto bg-black/60 p-4 sm:items-center">
      <div class="card w-full max-w-md p-5" role="dialog" aria-modal="true" aria-label="Daily expense">
        <div class="flex items-start justify-between gap-3">
          <div class="min-w-0">
            <h2 class="text-lg font-semibold text-white">{{ expense().category }}</h2>
            <p class="text-sm text-slate-400">{{ expense().requestedBy.name }} · {{ expense().date | bdDate: 'd MMM y' }}</p>
          </div>
          <span class="shrink-0 rounded-full border px-2 py-0.5 text-xs" [class]="statusClasses[expense().status]">{{ statusLabels[expense().status] }}</span>
        </div>

        <p class="mt-4 text-sm text-slate-400">Expense cost</p>
        <p class="text-2xl font-bold text-white">{{ expense().amount | taka }}</p>

        <dl class="mt-3 space-y-2 text-sm">
          @if (expense().other) { <div><dt class="text-slate-400">Category</dt><dd class="text-slate-200">Other: {{ expense().category }}</dd></div> }
          @if (expense().details) { <div><dt class="text-slate-400">Details</dt><dd class="whitespace-pre-line text-slate-200">{{ expense().details }}</dd></div> }
          <div><dt class="text-slate-400">Added</dt><dd class="text-slate-200">{{ expense().createdAt | bdDate }}</dd></div>
          @if (expense().receipt; as r) {
            <div>
              <dt class="text-slate-400">Bill</dt>
              <dd>
                @if (r.contentType.startsWith('image/')) {
                  <a [href]="r.url" target="_blank" rel="noopener"><img [src]="r.url" alt="Bill" class="mt-1 max-h-48 rounded-lg object-contain" /></a>
                } @else {
                  <a [href]="r.url" target="_blank" rel="noopener" class="text-brand-light hover:underline">Open the PDF</a>
                }
              </dd>
            </div>
          }
          @if (expense().status === 'REJECTED') {
            <div><dt class="text-slate-400">Reason for rejecting</dt><dd class="text-rose-300">{{ expense().rejectionReason }}</dd></div>
          }
          @if (expense().reviewedBy) {
            <div><dt class="text-slate-400">Decided by</dt><dd class="text-slate-200">{{ expense().reviewedBy }}, {{ expense().reviewedAt | bdDate }}</dd></div>
          }
        </dl>

        @if (canPay()) {
          <div class="mt-4 grid grid-cols-2 gap-3">
            <div>
              <label class="field-label" for="daily-paid">Paid (৳) *</label>
              <input id="daily-paid" type="number" min="0" step="0.01" class="field" [ngModel]="paid()" (ngModelChange)="paid.set($event)" />
            </div>
            <div>
              <p class="field-label">Due (৳)</p>
              <p class="field bg-transparent font-semibold" [class]="due() < 0 ? 'text-rose-300' : 'text-white'">{{ due() | taka }}</p>
            </div>
          </div>
          @if (due() < 0) { <p class="field-error mt-1">Paid can't be more than the expense</p> }
        } @else if (expense().status === 'APPROVED') {
          <div class="mt-4 grid grid-cols-2 gap-3 text-sm">
            <div><p class="text-slate-400">Paid</p><p class="font-semibold text-white">{{ expense().paid | taka }}</p></div>
            <div><p class="text-slate-400">Due</p><p class="font-semibold text-white">{{ expense().due ?? 0 | taka }}</p></div>
          </div>
        }

        @if (rejecting()) {
          <label class="field-label mt-4 block" for="daily-reason">Reason for rejecting *</label>
          <textarea id="daily-reason" rows="3" class="field" maxlength="500" [ngModel]="reason()" (ngModelChange)="reason.set($event)"></textarea>
        }

        <div class="mt-5 flex flex-wrap justify-end gap-2">
          <button type="button" class="btn-secondary" (click)="closed.emit(false)">{{ canDecide() || canPay() ? 'Cancel' : 'Close' }}</button>
          @if (rejecting()) {
            <button type="button" class="btn-primary" [disabled]="saving() || !reason().trim()" (click)="reject()">Reject expense</button>
          } @else {
            @if (canDecide()) {
              <button type="button" class="btn-secondary" (click)="rejecting.set(true)">Reject</button>
              <button type="button" class="btn-primary" [disabled]="!paidIsValid() || saving()" (click)="approve()">Approve</button>
            } @else if (canPay()) {
              <button type="button" class="btn-primary" [disabled]="!paidIsValid() || saving()" (click)="savePaid()">Save paid</button>
            }
          }
        </div>
      </div>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DailyExpenseReviewDialog implements OnInit {
  private readonly api = inject(DailyExpenseApi);
  private readonly toast = inject(ToastService);
  private readonly auth = inject(AuthService);

  readonly expense = input.required<DailyExpense>();
  /** True when something was saved. */
  readonly closed = output<boolean>();

  protected readonly statusLabels = DAILY_STATUS_LABELS;
  protected readonly statusClasses = DAILY_STATUS_CLASSES;
  protected readonly paid = signal<number | null>(null);
  protected readonly reason = signal('');
  protected readonly rejecting = signal(false);
  protected readonly saving = signal(false);

  private readonly mayApprove = computed(
    () => this.auth.hasPermission('DAILY_EXPENSE_APPROVE') && this.expense().requestedBy.id !== this.auth.user()?.id,
  );
  protected readonly canDecide = computed(() => this.mayApprove() && this.expense().status === 'PENDING');
  /** Entering what was paid: when approving, or later while something is still due. */
  protected readonly canPay = computed(
    () => !this.rejecting() && (this.canDecide() || (this.mayApprove() && this.expense().status === 'APPROVED' && (this.expense().due ?? 0) > 0)),
  );
  protected readonly due = computed(() => Math.round((this.expense().amount - (Number(this.paid()) || 0)) * 100) / 100);
  protected readonly paidIsValid = computed(() => {
    const paid = this.paid();
    return paid !== null && (paid as unknown) !== '' && Number(paid) >= 0 && this.due() >= 0;
  });

  ngOnInit(): void {
    if (this.expense().status === 'APPROVED') {
      this.paid.set(this.expense().paid);
    }
  }

  protected approve(): void {
    this.run(this.api.approve(this.expense().id, Number(this.paid())), 'Expense approved');
  }

  protected savePaid(): void {
    this.run(this.api.updatePaid(this.expense().id, Number(this.paid())), 'Paid updated');
  }

  protected reject(): void {
    this.run(this.api.reject(this.expense().id, this.reason().trim()), 'Expense rejected');
  }

  private run(request: Observable<unknown>, message: string): void {
    this.saving.set(true);
    request.subscribe({
      next: () => {
        this.toast.success(message);
        this.closed.emit(true);
      },
      error: (err: unknown) => {
        this.toast.error(errorMessage(err));
        this.saving.set(false);
      },
    });
  }
}
