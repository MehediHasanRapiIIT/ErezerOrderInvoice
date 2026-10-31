import { ChangeDetectionStrategy, Component, computed, inject, input, OnInit, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { errorMessage } from '../../core/http/api-error';
import { ToastService } from '../../core/notify/toast.service';
import { isoDate } from '../../shared/date-range';
import { bdToday } from '../../shared/ui/bd-date.pipe';
import { TakaPipe } from '../../shared/ui/taka.pipe';
import { BoostApi, BoostCost } from './data/boost.api';

/** Add or edit a day's boost spending: dollars × rate gives the taka. A new one starts at the average buying rate. */
@Component({
  selector: 'app-boost-cost-dialog',
  imports: [FormsModule, TakaPipe],
  template: `
    <div class="fixed inset-0 z-[85] flex items-start justify-center overflow-y-auto bg-black/60 p-4 sm:items-center">
      <form class="card w-full max-w-md p-5" (ngSubmit)="save()">
        <h2 class="text-lg font-semibold text-white">{{ cost() ? 'Edit cost' : 'Add cost' }}</h2>
        <p class="text-sm text-slate-400">What was spent on boosting that day.</p>

        <label class="field-label mt-4 block" for="boost-date">Date *</label>
        <input id="boost-date" name="date" type="date" class="field" [max]="today" [(ngModel)]="date" required />

        <div class="mt-3 grid grid-cols-2 gap-3">
          <div>
            <label class="field-label" for="boost-usd">Dollars ($) *</label>
            <input id="boost-usd" name="usd" type="number" min="0.01" step="0.01" class="field" [ngModel]="usd()" (ngModelChange)="usd.set($event)" required />
          </div>
          <div>
            <label class="field-label" for="boost-rate">Dollar rate (৳) *</label>
            <input id="boost-rate" name="rate" type="number" min="0.01" step="0.01" class="field" [ngModel]="rate()" (ngModelChange)="rate.set($event)" required />
          </div>
        </div>
        @if (!cost() && suggested(); as s) {
          <p class="mt-1 text-xs text-slate-500">Your average buying rate is ৳ {{ s }}. Change it if you paid a different rate.</p>
        }

        <p class="field-label mt-3">Total in BDT</p>
        <p class="text-2xl font-bold text-white">{{ bdt() | taka }}</p>

        <label class="field-label mt-3 block" for="boost-note">Note</label>
        <input id="boost-note" name="note" class="field" maxlength="200" placeholder="e.g. Eid sale post" [(ngModel)]="note" />

        <div class="mt-5 flex justify-end gap-2">
          <button type="button" class="btn-secondary" (click)="closed.emit(false)">Cancel</button>
          <button type="submit" class="btn-primary" [disabled]="saving() || bdt() <= 0">Save</button>
        </div>
      </form>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BoostCostDialog implements OnInit {
  private readonly api = inject(BoostApi);
  private readonly toast = inject(ToastService);

  /** The entry being edited; empty to add one. */
  readonly cost = input<BoostCost | null>(null);
  /** True when a cost was saved. */
  readonly closed = output<boolean>();

  protected readonly today = isoDate(bdToday());
  protected readonly usd = signal<number | null>(null);
  protected readonly rate = signal<number | null>(null);
  protected readonly suggested = signal<number | null>(null);
  protected readonly saving = signal(false);
  protected readonly bdt = computed(() => Math.round((Number(this.usd()) || 0) * (Number(this.rate()) || 0) * 100) / 100);
  protected date = this.today;
  protected note = '';

  ngOnInit(): void {
    const cost = this.cost();
    if (cost) {
      this.date = cost.date;
      this.usd.set(cost.usd);
      this.rate.set(cost.rate);
      this.note = cost.note ?? '';
      return;
    }
    this.api.rate().subscribe({
      next: ({ rate }) => {
        if (rate) {
          this.suggested.set(rate);
          if (this.rate() === null) this.rate.set(rate);
        }
      },
    });
  }

  protected save(): void {
    this.saving.set(true);
    const body = { date: this.date, usd: Number(this.usd()), rate: Number(this.rate()), note: this.note.trim() || null };
    const cost = this.cost();
    (cost ? this.api.updateCost(cost.id, { ...body, version: cost.version }) : this.api.addCost(body)).subscribe({
      next: () => {
        this.toast.success('Cost saved');
        this.closed.emit(true);
      },
      error: (err: unknown) => {
        this.toast.error(errorMessage(err));
        this.saving.set(false);
      },
    });
  }
}
