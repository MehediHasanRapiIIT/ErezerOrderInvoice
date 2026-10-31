import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, input, OnInit, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { errorMessage } from '../../core/http/api-error';
import { ToastService } from '../../core/notify/toast.service';
import { isoDate } from '../../shared/date-range';
import { bdToday } from '../../shared/ui/bd-date.pipe';
import { TakaPipe } from '../../shared/ui/taka.pipe';
import { BoostApi, BoostPurchase } from './data/boost.api';

interface Row {
  usd: number | null;
  bdt: number | null;
}

interface Result {
  usd: number;
  bdt: number;
  rate: number;
}

const MAX_ROWS = 50;

/** Add or edit dollars bought, in one or more parts: Calculate shows what each dollar cost, then it can be saved. */
@Component({
  selector: 'app-boost-buy-dialog',
  imports: [DecimalPipe, FormsModule, TakaPipe],
  template: `
    <div class="fixed inset-0 z-[85] flex items-start justify-center overflow-y-auto bg-black/60 p-4 sm:items-center">
      <form class="card w-full max-w-md p-5" (ngSubmit)="save()">
        <h2 class="text-lg font-semibold text-white">{{ purchase() ? 'Edit buy cost' : 'Buy cost' }}</h2>
        <p class="text-sm text-slate-400">Dollars you bought. Add a row for each part, such as $5 for ৳500 and $6 for ৳600.</p>

        <label class="field-label mt-4 block" for="buy-date">Date *</label>
        <input id="buy-date" name="date" type="date" class="field" [max]="today" [(ngModel)]="date" required />

        <div class="mt-3 space-y-2">
          @for (row of rows(); track row; let i = $index) {
            <div class="flex items-end gap-2">
              <div class="min-w-0 flex-1">
                <label class="field-label" [for]="'buy-usd-' + i">Dollars ($)</label>
                <input [id]="'buy-usd-' + i" [name]="'usd' + i" type="number" min="0.01" step="0.01" class="field" [ngModel]="row.usd" (ngModelChange)="change(row, 'usd', $event)" />
              </div>
              <div class="min-w-0 flex-1">
                <label class="field-label" [for]="'buy-bdt-' + i">Paid (৳)</label>
                <input [id]="'buy-bdt-' + i" [name]="'bdt' + i" type="number" min="0.01" step="0.01" class="field" [ngModel]="row.bdt" (ngModelChange)="change(row, 'bdt', $event)" />
              </div>
              <button type="button" class="btn-secondary px-3" [disabled]="rows().length === 1" [attr.aria-label]="'Remove row ' + (i + 1)" (click)="removeRow(row)"><i class="fas fa-times"></i></button>
            </div>
          }
        </div>
        <div class="mt-3 flex flex-wrap gap-2">
          <button type="button" class="btn-secondary" [disabled]="rows().length >= maxRows" (click)="addRow()"><i class="fas fa-plus"></i> Add row</button>
          <button type="button" class="btn-secondary" (click)="calculate()"><i class="fas fa-calculator"></i> Calculate</button>
        </div>

        @if (result(); as r) {
          <div class="mt-4 grid grid-cols-3 gap-2 rounded-lg border border-line p-3 text-sm">
            <div><p class="text-slate-400">Dollars</p><p class="font-semibold text-white">$ {{ r.usd | number: '1.2-2' }}</p></div>
            <div><p class="text-slate-400">Paid</p><p class="font-semibold text-white">{{ r.bdt | taka }}</p></div>
            <div><p class="text-slate-400">Per dollar</p><p class="font-semibold text-emerald-300">{{ r.rate | taka }}</p></div>
          </div>
        }

        <label class="field-label mt-3 block" for="buy-note">Note</label>
        <input id="buy-note" name="note" class="field" maxlength="200" placeholder="e.g. Bought from whom" [(ngModel)]="note" />

        <div class="mt-5 flex justify-end gap-2">
          <button type="button" class="btn-secondary" (click)="closed.emit(false)">Cancel</button>
          <button type="submit" class="btn-primary" [disabled]="saving() || !result()">Save</button>
        </div>
      </form>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BoostBuyDialog implements OnInit {
  private readonly api = inject(BoostApi);
  private readonly toast = inject(ToastService);

  /** The entry being edited; empty to add one. */
  readonly purchase = input<BoostPurchase | null>(null);
  /** True when a purchase was saved. */
  readonly closed = output<boolean>();

  protected readonly today = isoDate(bdToday());
  protected readonly maxRows = MAX_ROWS;
  protected readonly rows = signal<Row[]>([{ usd: null, bdt: null }]);
  /** Cleared by any change, so what is saved is always what was calculated. */
  protected readonly result = signal<Result | null>(null);
  protected readonly saving = signal(false);
  protected date = this.today;
  protected note = '';

  ngOnInit(): void {
    const purchase = this.purchase();
    if (purchase) {
      this.date = purchase.date;
      this.note = purchase.note ?? '';
      this.rows.set(purchase.lines.map((l) => ({ ...l })));
      this.calculate();
    }
  }

  protected change(row: Row, field: 'usd' | 'bdt', value: number | null): void {
    row[field] = value;
    this.result.set(null);
  }

  protected addRow(): void {
    this.rows.update((rows) => [...rows, { usd: null, bdt: null }]);
    this.result.set(null);
  }

  protected removeRow(row: Row): void {
    this.rows.update((rows) => rows.filter((r) => r !== row));
    this.result.set(null);
  }

  protected calculate(): void {
    const rows = this.rows();
    if (rows.some((r) => !(Number(r.usd) > 0) || !(Number(r.bdt) > 0))) {
      this.toast.error('Enter the dollars and the taka paid in every row');
      return;
    }
    const usd = rows.reduce((sum, r) => sum + Number(r.usd), 0);
    const bdt = rows.reduce((sum, r) => sum + Number(r.bdt), 0);
    this.result.set({ usd, bdt, rate: Math.round((bdt / usd) * 100) / 100 });
  }

  protected save(): void {
    if (!this.result()) return;
    this.saving.set(true);
    const body = {
      date: this.date,
      lines: this.rows().map((r) => ({ usd: Number(r.usd), bdt: Number(r.bdt) })),
      note: this.note.trim() || null,
    };
    const purchase = this.purchase();
    (purchase ? this.api.updatePurchase(purchase.id, { ...body, version: purchase.version }) : this.api.addPurchase(body))
      .subscribe({
        next: () => {
          this.toast.success('Purchase saved');
          this.closed.emit(true);
        },
        error: (err: unknown) => {
          this.toast.error(errorMessage(err));
          this.saving.set(false);
        },
      });
  }
}
