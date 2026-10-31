import { ChangeDetectionStrategy, Component, computed, inject, input, OnInit, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { errorMessage } from '../../core/http/api-error';
import { ToastService } from '../../core/notify/toast.service';
import { isoDate } from '../../shared/date-range';
import { bdToday } from '../../shared/ui/bd-date.pipe';
import { DailyExpenseApi } from './data/daily-expense.api';
import { DailyCategory, DailyExpense, OTHER_CATEGORY } from './data/daily-expense.models';

const MAX_FILE_BYTES = 10 * 1024 * 1024;
const FILE_TYPES = ['image/jpeg', 'image/png', 'image/webp', 'application/pdf'];

/** Add a daily expense, which sends it to the admin for approval, or edit one. */
@Component({
  selector: 'app-daily-expense-form-dialog',
  imports: [FormsModule],
  template: `
    <div class="fixed inset-0 z-[85] flex items-start justify-center overflow-y-auto bg-black/60 p-4 sm:items-center">
      <form class="card w-full max-w-md p-5" (ngSubmit)="save()">
        <h2 class="text-lg font-semibold text-white">{{ expense() ? 'Edit expense' : 'Add expense' }}</h2>
        <p class="text-sm text-slate-400">{{ expense() ? expense()!.requestedBy.name : 'It goes to the admin for approval.' }}</p>

        <div class="mt-4 grid grid-cols-2 gap-3">
          <div>
            <label class="field-label" for="daily-date">Date *</label>
            <input id="daily-date" name="date" type="date" class="field" [max]="today" [(ngModel)]="date" required />
          </div>
          <div>
            <label class="field-label" for="daily-amount">Amount (৳) *</label>
            <input id="daily-amount" name="amount" type="number" min="0.01" step="0.01" class="field" [(ngModel)]="amount" required />
          </div>
        </div>

        <label class="field-label mt-3 block" for="daily-category">Category *</label>
        <select id="daily-category" name="category" class="field" [ngModel]="categoryId()" (ngModelChange)="categoryId.set($event)" required>
          <option [ngValue]="null" disabled>Select</option>
          @for (c of choices(); track c.id) {
            <option [ngValue]="c.id">{{ c.name }}</option>
          }
          <option [ngValue]="otherValue">Other</option>
        </select>

        @if (categoryId() === otherValue) {
          <label class="field-label mt-3 block" for="daily-other">What was it for? *</label>
          <input id="daily-other" name="other" class="field" maxlength="60" [(ngModel)]="other" required />
        }

        <label class="field-label mt-3 block" for="daily-details">Details</label>
        <textarea id="daily-details" name="details" rows="3" class="field" maxlength="500" [(ngModel)]="details"></textarea>

        <p class="field-label mt-3">Bill or receipt</p>
        @if (receipt(); as r) {
          <div class="flex items-center gap-3 rounded-lg border border-line p-2 text-sm">
            <a [href]="r.url" target="_blank" rel="noopener" class="min-w-0 flex-1 truncate text-brand-light hover:underline">{{ r.name }}</a>
            <button type="button" class="cursor-pointer rounded p-2 text-red-300 hover:bg-red-500/10" aria-label="Remove bill" (click)="receipt.set(null)"><i class="fas fa-times"></i></button>
          </div>
        } @else {
          <label class="flex cursor-pointer items-center gap-3 rounded-lg border border-dashed border-line p-3 text-sm text-slate-400 hover:text-white">
            <i class="fas" [class.fa-receipt]="!uploading()" [class.fa-circle-notch]="uploading()" [class.fa-spin]="uploading()"></i>
            {{ uploading() ? 'Uploading…' : expense()?.receipt ? 'Has a bill. Choose a file to replace it.' : 'Add a photo or PDF (optional)' }}
            <input type="file" class="sr-only" accept="image/jpeg,image/png,image/webp,application/pdf" [disabled]="uploading()" (change)="onFile($any($event.target))" />
          </label>
        }

        <div class="mt-5 flex justify-end gap-2">
          <button type="button" class="btn-secondary" (click)="closed.emit(false)">Cancel</button>
          <button type="submit" class="btn-primary" [disabled]="saving() || uploading()">{{ expense() ? 'Save changes' : 'Send for approval' }}</button>
        </div>
      </form>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DailyExpenseFormDialog implements OnInit {
  private readonly api = inject(DailyExpenseApi);
  private readonly toast = inject(ToastService);

  readonly categories = input.required<DailyCategory[]>();
  /** The expense being edited; empty to add one. */
  readonly expense = input<DailyExpense | null>(null);
  /** True when an expense was saved. */
  readonly closed = output<boolean>();

  protected readonly otherValue = OTHER_CATEGORY;
  protected readonly today = isoDate(bdToday());
  /** Active categories, plus the expense's own if it has since been switched off. */
  protected readonly choices = computed(() => this.categories().filter((c) => c.active || c.id === this.expense()?.categoryId));
  protected readonly categoryId = signal<number | null>(null);
  protected readonly receipt = signal<{ id: number; url: string; name: string } | null>(null);
  protected readonly uploading = signal(false);
  protected readonly saving = signal(false);
  protected date = this.today;
  protected amount: number | null = null;
  protected other = '';
  protected details = '';

  ngOnInit(): void {
    const expense = this.expense();
    if (expense) {
      this.date = expense.date;
      this.amount = expense.amount;
      this.categoryId.set(expense.other ? OTHER_CATEGORY : (expense.categoryId ?? null));
      this.other = expense.other ? expense.category : '';
      this.details = expense.details ?? '';
    }
  }

  protected onFile(element: HTMLInputElement): void {
    const file = element.files?.[0];
    element.value = '';
    if (!file) return;
    if (!FILE_TYPES.includes(file.type)) {
      this.toast.error('The bill must be a photo or a PDF');
      return;
    }
    if (file.size > MAX_FILE_BYTES) {
      this.toast.error('The bill must be 10 MB or smaller');
      return;
    }
    this.uploading.set(true);
    this.api.uploadReceipt(file).subscribe({
      next: (uploaded) => {
        this.receipt.set({ id: uploaded.id, url: uploaded.url, name: file.name });
        this.uploading.set(false);
      },
      error: (err: unknown) => {
        this.toast.error(errorMessage(err));
        this.uploading.set(false);
      },
    });
  }

  protected save(): void {
    const categoryId = this.categoryId();
    const isOther = categoryId === OTHER_CATEGORY;
    if (!this.date || !this.amount || this.amount <= 0) {
      this.toast.error('Enter the date and the amount');
      return;
    }
    if (categoryId === null || (isOther && !this.other.trim())) {
      this.toast.error(isOther ? 'Write what the expense was for' : 'Choose a category');
      return;
    }
    this.saving.set(true);
    const body = {
      date: this.date,
      categoryId: isOther ? null : categoryId,
      otherCategory: isOther ? this.other.trim() : null,
      amount: this.amount,
      details: this.details.trim() || null,
      receiptFileId: this.receipt()?.id ?? null,
    };
    const expense = this.expense();
    (expense ? this.api.update(expense.id, { ...body, version: expense.version }) : this.api.create(body))
      .subscribe({
        next: () => {
          this.toast.success(expense ? 'Changes saved' : 'Sent for approval');
          this.closed.emit(true);
        },
        error: (err: unknown) => {
          this.toast.error(errorMessage(err));
          this.saving.set(false);
        },
      });
  }
}
