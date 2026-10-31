import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../core/auth/auth.service';
import { errorMessage } from '../../core/http/api-error';
import { ToastService } from '../../core/notify/toast.service';
import { BdDatePipe } from '../../shared/ui/bd-date.pipe';
import { ConfirmService } from '../../shared/ui/confirm-dialog';
import { Pagination } from '../../shared/ui/pagination';
import { Reject, REJECT_KIND_LABELS, REJECT_KINDS, RejectApi, RejectFilter, RejectKind } from './data/reject.api';
import { RejectFormDialog } from './reject-form-dialog';

const PAGE_SIZE = 20;
const DEBOUNCE_MS = 350;

/** Rejected pieces: what they were, whether they came out of stock, and how many. */
@Component({
  selector: 'app-reject-page',
  imports: [BdDatePipe, FormsModule, Pagination, RejectFormDialog],
  template: `
    <div class="flex flex-wrap items-center justify-between gap-4">
      <div>
        <h1 class="page-title">Reject Track</h1>
        <p class="page-subtitle">Rejected pieces. Those from stock are taken out of it.</p>
      </div>
      @if (canAdd) {
        <button type="button" class="btn-primary" (click)="adding.set(true)"><i class="fas fa-plus"></i> Add Reject</button>
      }
    </div>

    <div class="mt-6 grid grid-cols-2 gap-4 lg:grid-cols-4">
      <div class="card p-5">
        <p class="text-sm text-slate-400"><i class="fas fa-ban mr-1 text-rose-300"></i>Total rejected</p>
        <p class="mt-1 text-2xl font-bold text-white">{{ summary()?.total ?? 0 }}</p>
        <p class="mt-1 text-xs text-slate-500">pieces in {{ summary()?.entries ?? 0 }} entr{{ summary()?.entries === 1 ? 'y' : 'ies' }}</p>
      </div>
      <div class="card p-5">
        <p class="text-sm text-slate-400"><i class="fas fa-fill-drip mr-1 text-sky-300"></i>Solid Color</p>
        <p class="mt-1 text-2xl font-bold text-white">{{ summary()?.solid ?? 0 }}</p>
        <p class="mt-1 text-xs text-slate-500">pieces</p>
      </div>
      <div class="card p-5">
        <p class="text-sm text-slate-400"><i class="fas fa-print mr-1 text-amber-300"></i>Printed</p>
        <p class="mt-1 text-2xl font-bold text-white">{{ summary()?.printed ?? 0 }}</p>
        <p class="mt-1 text-xs text-slate-500">pieces</p>
      </div>
      <div class="card p-5">
        <p class="text-sm text-slate-400"><i class="fas fa-boxes mr-1 text-emerald-300"></i>Taken from stock</p>
        <p class="mt-1 text-2xl font-bold text-white">{{ summary()?.fromStock ?? 0 }}</p>
        <p class="mt-1 text-xs text-slate-500">{{ summary()?.other ?? 0 }} other, not from stock</p>
      </div>
    </div>

    <section class="card mt-6 overflow-hidden">
      <div class="grid gap-2 border-b border-line p-4 sm:grid-cols-2 lg:grid-cols-6">
        <input type="search" class="field lg:col-span-2" placeholder="Search variant or note…" [value]="search()" (input)="debounced($any($event.target).value)" aria-label="Search" />
        <select class="field" aria-label="Type" [ngModel]="kind()" (ngModelChange)="kind.set($event)">
          <option [ngValue]="null">All types</option>
          @for (k of kinds; track k) { <option [ngValue]="k">{{ kindLabels[k] }}</option> }
        </select>
        <select class="field" aria-label="Source" [ngModel]="fromStock()" (ngModelChange)="fromStock.set($event)">
          <option [ngValue]="null">Stock and Other</option>
          <option [ngValue]="true">From stock</option>
          <option [ngValue]="false">Other</option>
        </select>
        <input type="date" class="field" aria-label="From date" [max]="to() || null" [ngModel]="from()" (ngModelChange)="from.set($event || '')" />
        <input type="date" class="field" aria-label="To date" [min]="from() || null" [ngModel]="to()" (ngModelChange)="to.set($event || '')" />
      </div>

      <div class="overflow-x-auto">
        <table class="phone-cards w-full min-w-[820px] text-left text-sm">
          <thead class="border-b border-line text-slate-400">
            <tr>
              <th class="px-4 py-3 font-semibold">Variant</th>
              <th class="px-4 py-3 font-semibold">Date</th>
              <th class="px-4 py-3 font-semibold">Size</th>
              <th class="px-4 py-3 font-semibold">Type</th>
              <th class="px-4 py-3 text-right font-semibold">Quantity</th>
              <th class="px-4 py-3 font-semibold">Source</th>
              <th class="px-4 py-3 font-semibold">Added by</th>
              @if (canEdit || canDelete) { <th class="px-4 py-3"><span class="sr-only">Actions</span></th> }
            </tr>
          </thead>
          <tbody>
            @for (r of rows(); track r.id) {
              <tr class="border-b border-line/60">
                <td class="py-3 pl-4 pr-28 md:pr-4">
                  <span class="font-medium text-white">{{ r.variantName }}</span>
                  @if (r.note) { <span class="block max-w-xs truncate text-xs text-slate-400">{{ r.note }}</span> }
                </td>
                <td class="cell-inline whitespace-nowrap px-4 py-3 text-slate-200" data-label="Date">{{ r.date | bdDate: 'd MMM y' }}</td>
                <td class="cell-inline px-4 py-3 text-slate-200" data-label="Size">{{ r.size || '–' }}</td>
                <td class="cell-inline whitespace-nowrap px-4 py-3 text-slate-200" data-label="Type">{{ kindLabels[r.kind] }}</td>
                <td class="cell-inline px-4 py-3 text-right font-semibold text-white" data-label="Quantity">{{ r.quantity }}</td>
                <td class="cell-inline whitespace-nowrap px-4 py-3" data-label="Source">
                  <span class="rounded-full border px-2 py-0.5 text-xs" [class]="r.fromStock ? 'border-emerald-500/40 bg-emerald-500/10 text-emerald-300' : 'border-line bg-white/5 text-slate-300'">{{ r.fromStock ? 'From stock' : 'Other' }}</span>
                </td>
                <td class="cell-inline px-4 py-3 text-slate-300" data-label="Added by">{{ r.addedBy || '–' }}</td>
                @if (canEdit || canDelete) {
                  <td class="cell-actions px-4 py-3 text-right">
                    <div class="flex justify-end gap-2">
                      @if (canEdit) {
                        <button type="button" class="btn-secondary" title="Edit" [attr.aria-label]="'Edit ' + r.variantName" (click)="editing.set(r)"><i class="fas fa-pen"></i></button>
                      }
                      @if (canDelete) {
                        <button type="button" class="btn-secondary" title="Delete" [attr.aria-label]="'Delete ' + r.variantName" (click)="remove(r)"><i class="fas fa-trash"></i></button>
                      }
                    </div>
                  </td>
                }
              </tr>
            } @empty {
              <tr><td colspan="8" class="px-4 py-10 text-center text-slate-500">
                @if (list.isLoading()) { Loading… } @else if (list.error()) { Couldn't load the rejects. } @else { No rejects found. }
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

    @if (adding()) {
      <app-reject-form-dialog (closed)="closed(adding, $event)" />
    }
    @if (editing(); as r) {
      <app-reject-form-dialog [reject]="r" (closed)="closedEdit($event)" />
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RejectPage {
  private readonly api = inject(RejectApi);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);

  protected readonly canAdd = this.auth.hasPermission('REJECT_CREATE');
  protected readonly canEdit = this.auth.hasPermission('REJECT_EDIT');
  protected readonly canDelete = this.auth.hasPermission('REJECT_DELETE');
  protected readonly kinds = REJECT_KINDS;
  protected readonly kindLabels = REJECT_KIND_LABELS;

  protected readonly search = signal('');
  protected readonly kind = signal<RejectKind | null>(null);
  protected readonly fromStock = signal<boolean | null>(null);
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly adding = signal(false);
  protected readonly editing = signal<Reject | null>(null);

  private readonly filter = computed<RejectFilter>(() => ({
    from: this.from(),
    to: this.to(),
    q: this.search(),
    kind: this.kind(),
    fromStock: this.fromStock(),
  }));
  protected readonly page = linkedSignal({ source: this.filter, computation: () => 0 });

  protected readonly list = rxResource({
    params: () => ({ filter: this.filter(), page: this.page() }),
    stream: ({ params }) => this.api.list(params.filter, params.page, PAGE_SIZE),
  });
  private readonly totals = rxResource({ params: () => this.filter(), stream: ({ params }) => this.api.summary(params) });

  protected readonly result = computed(() => (this.list.hasValue() ? this.list.value() : undefined));
  protected readonly rows = computed(() => this.result()?.content ?? []);
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
      this.list.reload();
    }
    this.totals.reload();
  }

  protected closed(flag: { set(value: boolean): void }, saved: boolean): void {
    flag.set(false);
    if (saved) {
      this.refresh();
    }
  }

  protected closedEdit(saved: boolean): void {
    this.editing.set(null);
    if (saved) {
      this.list.reload();
      this.totals.reload();
    }
  }

  protected async remove(reject: Reject): Promise<void> {
    const confirmed = await this.confirm.ask({
      title: `Delete this reject of ${reject.variantName}?`,
      message: reject.fromStock ? `The ${reject.quantity} piece${reject.quantity === 1 ? '' : 's'} go back into stock.` : 'It is removed from the list.',
      danger: true,
    });
    if (!confirmed) return;
    this.api.delete(reject.id).subscribe({
      next: () => {
        this.toast.success('Reject deleted');
        this.refresh();
      },
      error: (err: unknown) => this.toast.error(errorMessage(err)),
    });
  }
}
