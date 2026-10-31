import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Observable, of } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { errorMessage } from '../../core/http/api-error';
import { ToastService } from '../../core/notify/toast.service';
import { BdDatePipe } from '../../shared/ui/bd-date.pipe';
import { ConfirmService } from '../../shared/ui/confirm-dialog';
import { Pagination } from '../../shared/ui/pagination';
import { TakaPipe } from '../../shared/ui/taka.pipe';
import { BoostBuyDialog } from './boost-buy-dialog';
import { BoostCostDialog } from './boost-cost-dialog';
import { BoostApi, BoostCost, BoostFilter, BoostPurchase } from './data/boost.api';

const PAGE_SIZE = 20;
const DEBOUNCE_MS = 350;

/** Boost spending, and the dollars bought to pay for it. */
@Component({
  selector: 'app-boost-page',
  imports: [BdDatePipe, BoostBuyDialog, BoostCostDialog, DecimalPipe, FormsModule, Pagination, TakaPipe],
  template: `
    <div class="flex flex-wrap items-center justify-between gap-4">
      <div>
        <h1 class="page-title">Expense (Boost)</h1>
        <p class="page-subtitle">What is spent on boosting each day, and the dollars bought for it.</p>
      </div>
      <div class="flex flex-wrap gap-2">
        @if (canBuy) {
          <button type="button" class="btn-secondary" (click)="showBuy.set(true)"><i class="fas fa-dollar-sign"></i> Buy cost</button>
        }
        @if (canAdd) {
          <button type="button" class="btn-primary" (click)="showCost.set(true)"><i class="fas fa-plus"></i> Add cost</button>
        }
      </div>
    </div>

    <div class="mt-6 grid grid-cols-2 gap-4" [class.lg:grid-cols-4]="canBuy">
      <div class="card p-5">
        <p class="text-sm text-slate-400"><i class="fas fa-bullhorn mr-1 text-brand-light"></i>Boost spent</p>
        <p class="mt-1 flex flex-wrap items-baseline gap-x-3 text-2xl font-bold text-white">
          <span>$ {{ summary()?.spentUsd ?? 0 | number: '1.2-2' }}</span>
          <span>{{ summary()?.spentBdt ?? 0 | taka }}</span>
        </p>
        <p class="mt-1 text-xs text-slate-500">in {{ summary()?.costCount ?? 0 }} entr{{ summary()?.costCount === 1 ? 'y' : 'ies' }}</p>
      </div>
      @if (canBuy) {
        <div class="card p-5">
          <p class="text-sm text-slate-400"><i class="fas fa-dollar-sign mr-1 text-emerald-300"></i>Dollars bought</p>
          <p class="mt-1 text-2xl font-bold text-white">$ {{ summary()?.boughtUsd ?? 0 | number: '1.2-2' }}</p>
          <p class="mt-1 text-xs text-slate-500">{{ summary()?.boughtBdt ?? 0 | taka }} paid</p>
        </div>
        <div class="card p-5">
          <p class="text-sm text-slate-400"><i class="fas fa-calculator mr-1 text-sky-300"></i>Average per dollar</p>
          <p class="mt-1 text-2xl font-bold text-white">{{ summary()?.averageRate ?? 0 | taka }}</p>
          <p class="mt-1 text-xs text-slate-500">all purchases</p>
        </div>
        <div class="card p-5">
          <p class="text-sm text-slate-400"><i class="fas fa-piggy-bank mr-1 text-amber-300"></i>Dollar balance</p>
          <p class="mt-1 text-2xl font-bold" [class]="(summary()?.balanceUsd ?? 0) < 0 ? 'text-rose-300' : 'text-white'">$ {{ summary()?.balanceUsd ?? 0 | number: '1.2-2' }}</p>
          <p class="mt-1 text-xs text-slate-500">{{ (summary()?.balanceUsd ?? 0) < 0 ? 'more spent than bought: add a Buy cost' : 'bought minus spent, all time' }}</p>
        </div>
      }
    </div>

    @if (canBuy) {
      <div class="mt-6 flex flex-wrap gap-2" role="tablist">
        <button type="button" role="tab" [attr.aria-selected]="tab() === 'costs'" [class]="tab() === 'costs' ? 'btn-primary' : 'btn-secondary'" (click)="tab.set('costs')">
          <i class="fas fa-bullhorn"></i> Boost costs
        </button>
        <button type="button" role="tab" [attr.aria-selected]="tab() === 'purchases'" [class]="tab() === 'purchases' ? 'btn-primary' : 'btn-secondary'" (click)="tab.set('purchases')">
          <i class="fas fa-dollar-sign"></i> Dollars bought
        </button>
      </div>
    }

    <section class="card mt-4 overflow-hidden">
      <div class="grid gap-2 border-b border-line p-4 sm:grid-cols-4">
        <input type="search" class="field sm:col-span-2" placeholder="Search notes…" [value]="search()" (input)="debounced($any($event.target).value)" aria-label="Search notes" />
        <input type="date" class="field" aria-label="From date" [max]="to() || null" [ngModel]="from()" (ngModelChange)="from.set($event || '')" />
        <input type="date" class="field" aria-label="To date" [min]="from() || null" [ngModel]="to()" (ngModelChange)="to.set($event || '')" />
      </div>

      <div class="overflow-x-auto">
        @if (tab() === 'costs') {
          <table class="phone-cards w-full min-w-[760px] text-left text-sm">
            <thead class="border-b border-line text-slate-400">
              <tr>
                <th class="px-4 py-3 font-semibold">Date</th>
                <th class="px-4 py-3 text-right font-semibold">Dollars</th>
                <th class="px-4 py-3 text-right font-semibold">Rate</th>
                <th class="px-4 py-3 text-right font-semibold">BDT</th>
                <th class="px-4 py-3 font-semibold">Note</th>
                <th class="px-4 py-3 font-semibold">Added by</th>
                @if (canEdit || canDelete) { <th class="px-4 py-3"><span class="sr-only">Actions</span></th> }
              </tr>
            </thead>
            <tbody>
              @for (c of costRows(); track c.id) {
                <tr class="border-b border-line/60">
                  <td class="whitespace-nowrap px-4 py-3 font-medium text-white">{{ c.date | bdDate: 'd MMM y' }}</td>
                  <td class="cell-inline whitespace-nowrap px-4 py-3 text-right text-slate-200" data-label="Dollars">$ {{ c.usd | number: '1.2-2' }}</td>
                  <td class="cell-inline whitespace-nowrap px-4 py-3 text-right text-slate-200" data-label="Rate">{{ c.rate | taka }}</td>
                  <td class="cell-inline whitespace-nowrap px-4 py-3 text-right font-semibold text-white" data-label="BDT">{{ c.bdt | taka }}</td>
                  <td class="cell-inline px-4 py-3 text-slate-300" data-label="Note">{{ c.note || '–' }}</td>
                  <td class="cell-inline px-4 py-3 text-slate-300" data-label="Added by">{{ c.addedBy || '–' }}</td>
                  @if (canEdit || canDelete) {
                    <td class="cell-actions px-4 py-3 text-right">
                      <div class="flex justify-end gap-2">
                        @if (canEdit) {
                          <button type="button" class="btn-secondary" aria-label="Edit this cost" (click)="editCost(c)"><i class="fas fa-pen"></i></button>
                        }
                        @if (canDelete) {
                          <button type="button" class="btn-secondary" aria-label="Delete this cost" (click)="removeCost(c)"><i class="fas fa-trash"></i></button>
                        }
                      </div>
                    </td>
                  }
                </tr>
              } @empty {
                <tr><td colspan="7" class="px-4 py-10 text-center text-slate-500">{{ costs.isLoading() ? 'Loading…' : 'No boost costs found.' }}</td></tr>
              }
            </tbody>
          </table>
        } @else {
          <table class="phone-cards w-full min-w-[760px] text-left text-sm">
            <thead class="border-b border-line text-slate-400">
              <tr>
                <th class="px-4 py-3 font-semibold">Date</th>
                <th class="px-4 py-3 text-right font-semibold">Dollars</th>
                <th class="px-4 py-3 text-right font-semibold">Paid</th>
                <th class="px-4 py-3 text-right font-semibold">Per dollar</th>
                <th class="px-4 py-3 font-semibold">Parts</th>
                <th class="px-4 py-3 font-semibold">Note</th>
                @if (canEdit || canDelete) { <th class="px-4 py-3"><span class="sr-only">Actions</span></th> }
              </tr>
            </thead>
            <tbody>
              @for (p of purchaseRows(); track p.id) {
                <tr class="border-b border-line/60">
                  <td class="whitespace-nowrap px-4 py-3 font-medium text-white">{{ p.date | bdDate: 'd MMM y' }}</td>
                  <td class="cell-inline whitespace-nowrap px-4 py-3 text-right text-slate-200" data-label="Dollars">$ {{ p.usd | number: '1.2-2' }}</td>
                  <td class="cell-inline whitespace-nowrap px-4 py-3 text-right text-slate-200" data-label="Paid">{{ p.bdt | taka }}</td>
                  <td class="cell-inline whitespace-nowrap px-4 py-3 text-right font-semibold text-white" data-label="Per dollar">{{ p.rate | taka }}</td>
                  <td class="cell-inline px-4 py-3 text-xs text-slate-300" data-label="Parts">
                    @for (l of p.lines; track $index) {
                      <span class="block whitespace-nowrap">$ {{ l.usd | number: '1.2-2' }} for {{ l.bdt | taka }}</span>
                    }
                  </td>
                  <td class="cell-inline px-4 py-3 text-slate-300" data-label="Note">{{ p.note || '–' }}</td>
                  @if (canEdit || canDelete) {
                    <td class="cell-actions px-4 py-3 text-right">
                      <div class="flex justify-end gap-2">
                        @if (canEdit) {
                          <button type="button" class="btn-secondary" aria-label="Edit this purchase" (click)="editPurchase(p)"><i class="fas fa-pen"></i></button>
                        }
                        @if (canDelete) {
                          <button type="button" class="btn-secondary" aria-label="Delete this purchase" (click)="removePurchase(p)"><i class="fas fa-trash"></i></button>
                        }
                      </div>
                    </td>
                  }
                </tr>
              } @empty {
                <tr><td colspan="7" class="px-4 py-10 text-center text-slate-500">{{ purchases.isLoading() ? 'Loading…' : 'No purchases found.' }}</td></tr>
              }
            </tbody>
          </table>
        }
      </div>

      @if (result(); as r) {
        @if (r.totalElements > 0) {
          <div class="border-t border-line px-4 py-3">
            <app-pagination [page]="r.page" [size]="r.size" [totalElements]="r.totalElements" [totalPages]="r.totalPages" (pageChange)="page.set($event)" />
          </div>
        }
      }
    </section>

    @if (showCost()) {
      <app-boost-cost-dialog [cost]="editingCost()" (closed)="dialogClosed(showCost, $event)" />
    }
    @if (showBuy()) {
      <app-boost-buy-dialog [purchase]="editingPurchase()" (closed)="dialogClosed(showBuy, $event)" />
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BoostPage {
  private readonly api = inject(BoostApi);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);

  protected readonly canAdd = this.auth.hasPermission('BOOST_COST_ADD');
  protected readonly canBuy = this.auth.hasPermission('BOOST_BUY');
  protected readonly canEdit = this.auth.hasPermission('BOOST_EDIT');
  protected readonly canDelete = this.auth.hasPermission('BOOST_DELETE');

  protected readonly tab = signal<'costs' | 'purchases'>('costs');
  protected readonly search = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly showCost = signal(false);
  protected readonly showBuy = signal(false);
  protected readonly editingCost = signal<BoostCost | null>(null);
  protected readonly editingPurchase = signal<BoostPurchase | null>(null);

  private readonly filter = computed<BoostFilter>(() => ({ from: this.from(), to: this.to(), q: this.search() }));
  protected readonly page = linkedSignal({ source: () => [this.filter(), this.tab()], computation: () => 0 });

  protected readonly costs = rxResource({
    params: () => (this.tab() === 'costs' ? { filter: this.filter(), page: this.page() } : undefined),
    stream: ({ params }) => this.api.costs(params.filter, params.page, PAGE_SIZE),
  });
  protected readonly purchases = rxResource({
    params: () => (this.tab() === 'purchases' ? { filter: this.filter(), page: this.page() } : undefined),
    stream: ({ params }) => this.api.purchases(params.filter, params.page, PAGE_SIZE),
  });
  private readonly totals = rxResource({ params: () => this.filter(), stream: ({ params }) => this.api.summary(params) });

  private readonly costPage = computed(() => (this.costs.hasValue() ? this.costs.value() : undefined));
  private readonly purchasePage = computed(() => (this.purchases.hasValue() ? this.purchases.value() : undefined));
  protected readonly costRows = computed(() => this.costPage()?.content ?? []);
  protected readonly purchaseRows = computed(() => this.purchasePage()?.content ?? []);
  protected readonly result = computed(() => (this.tab() === 'costs' ? this.costPage() : this.purchasePage()));
  protected readonly summary = computed(() => (this.totals.hasValue() ? this.totals.value() : undefined));

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
      (this.tab() === 'costs' ? this.costs : this.purchases).reload();
    }
    this.totals.reload();
  }

  protected dialogClosed(flag: { set(value: boolean): void }, saved: boolean): void {
    flag.set(false);
    this.editingCost.set(null);
    this.editingPurchase.set(null);
    if (saved) {
      this.refresh();
    }
  }

  protected editCost(cost: BoostCost): void {
    this.editingCost.set(cost);
    this.showCost.set(true);
  }

  protected editPurchase(purchase: BoostPurchase): void {
    this.editingPurchase.set(purchase);
    this.showBuy.set(true);
  }

  protected removeCost(cost: BoostCost): void {
    void this.remove(`Delete the $${cost.usd} cost?`, this.api.deleteCost(cost.id));
  }

  protected removePurchase(purchase: BoostPurchase): void {
    void this.remove(`Delete the $${purchase.usd} purchase?`, this.api.deletePurchase(purchase.id));
  }

  private async remove(title: string, request: Observable<void>): Promise<void> {
    const confirmed = await this.confirm.ask({ title, message: 'This changes the totals and can\'t be undone.', danger: true });
    if (!confirmed) return;
    request.subscribe({
      next: () => {
        this.toast.success('Deleted');
        this.refresh();
      },
      error: (err: unknown) => this.toast.error(errorMessage(err)),
    });
  }
}
