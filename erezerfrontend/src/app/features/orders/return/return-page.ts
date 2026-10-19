import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { errorMessage } from '../../../core/http/api-error';
import { ToastService } from '../../../core/notify/toast.service';
import { BdDatePipe } from '../../../shared/ui/bd-date.pipe';
import { TakaPipe } from '../../../shared/ui/taka.pipe';
import { OrderApi } from '../data/order.api';
import { RETURN_REASONS } from '../data/order-labels';
import { DeliveryPaidBy, ExchangeCandidate, ReturnableItem, ReturnOptions, ReturnReason } from '../data/order.models';

/** One delivered line item and how many pieces come back. */
interface Line {
  item: ReturnableItem;
  selected: boolean;
  quantity: number;
}

/**
 * Return products of a delivered order: choose what comes back, why, the return charge and who pays it. A charge
 * the customer pays is taken off the refund; Erezer's share is a cost.
 */
@Component({
  selector: 'app-return-page',
  imports: [FormsModule, RouterLink, TakaPipe, BdDatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './return-page.html',
})
export class ReturnPage implements OnInit {
  private readonly api = inject(OrderApi);
  private readonly toast = inject(ToastService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  /** Refused parcels are recorded by marking the order Returned, not here. */
  protected readonly reasons = RETURN_REASONS.filter((r) => r.value !== 'REFUSED');

  // Step 1: find the order
  protected query = '';
  protected readonly searching = signal(false);
  protected readonly searched = signal(false);
  protected readonly candidates = signal<ExchangeCandidate[]>([]);

  // Step 2: the return
  protected readonly loading = signal(false);
  protected readonly options = signal<ReturnOptions | null>(null);
  protected readonly lines = signal<Line[]>([]);
  protected readonly reason = signal<ReturnReason>('DID_NOT_LIKE');
  protected readonly paidBy = signal<DeliveryPaidBy>('CUSTOMER');
  protected readonly returnCharge = signal<number | null>(0);
  protected readonly restock = signal(true);
  protected note = '';
  protected readonly saving = signal(false);

  protected readonly selected = computed(() => this.lines().filter((l) => l.selected));
  protected readonly pieces = computed(() => this.selected().reduce((sum, l) => sum + (l.quantity || 0), 0));
  protected readonly itemsValue = computed(() =>
    round(this.selected().reduce((sum, l) => sum + l.item.unitCredit * (l.quantity || 0), 0)),
  );
  protected readonly charge = computed(() => Math.max(Number(this.returnCharge()) || 0, 0));
  protected readonly customerShare = computed(() => (this.paidBy() === 'CUSTOMER' ? this.charge() : 0));
  protected readonly refund = computed(() => round(Math.max(this.itemsValue() - this.customerShare(), 0)));
  protected readonly wholeOrder = computed(() => {
    const open = this.lines().filter((l) => l.item.remaining > 0);
    return open.length > 0 && open.every((l) => l.selected && l.quantity === l.item.remaining);
  });
  protected readonly problems = computed(() => {
    const result: string[] = [];
    if (!this.options()) return result;
    if (!this.selected().length) result.push('Choose at least one product to return');
    for (const l of this.selected()) {
      if (!(l.quantity >= 1 && l.quantity <= l.item.remaining)) result.push(`${l.item.productName}: quantity must be 1–${l.item.remaining}`);
    }
    if (this.returnCharge() === null || `${this.returnCharge()}` === '' || Number(this.returnCharge()) < 0) {
      result.push('Enter the return charge (0 if none)');
    }
    return result;
  });

  ngOnInit(): void {
    const orderId = Number(this.route.snapshot.queryParamMap.get('order'));
    if (orderId) {
      this.load(orderId);
    }
  }

  protected search(): void {
    const q = this.query.trim();
    if (q.length < 3) {
      this.toast.info('Enter an order number or phone number');
      return;
    }
    this.searching.set(true);
    this.api.lookupExchange(q).subscribe({
      next: (found) => {
        this.searching.set(false);
        this.searched.set(true);
        this.candidates.set(found);
        if (found.length === 1) {
          this.load(found[0].id);
        }
      },
      error: (err: unknown) => {
        this.searching.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected load(orderId: number): void {
    this.loading.set(true);
    this.api.returnOptions(orderId).subscribe({
      next: (o) => {
        this.loading.set(false);
        this.options.set(o);
        this.lines.set(o.items.map((item) => ({ item, selected: false, quantity: item.remaining })));
        this.setReason('DID_NOT_LIKE');
        this.returnCharge.set(0);
        this.note = '';
      },
      error: (err: unknown) => {
        this.loading.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected startOver(): void {
    this.options.set(null);
    this.lines.set([]);
    void this.router.navigate([], { queryParams: {} });
  }

  protected update(line: Line, patch: Partial<Line>): void {
    this.lines.update((lines) => lines.map((l) => (l.item.id === line.item.id ? { ...l, ...patch } : l)));
  }

  protected selectAll(): void {
    this.lines.update((lines) =>
      lines.map((l) => (l.item.remaining > 0 ? { ...l, selected: true, quantity: l.item.remaining } : l)),
    );
  }

  /** The reason suggests who pays and whether the pieces can go back on the shelf; both stay editable. */
  protected setReason(value: ReturnReason): void {
    const r = RETURN_REASONS.find((x) => x.value === value);
    this.reason.set(value);
    if (r) {
      this.paidBy.set(r.suggestedPayer);
      this.restock.set(r.resellable);
    }
  }

  protected submit(): void {
    const o = this.options();
    if (!o || this.problems().length) return;
    this.saving.set(true);
    this.api
      .createReturn(o.orderId, {
        items: this.selected().map((l) => ({ orderItemId: l.item.id, quantity: l.quantity })),
        reason: this.reason(),
        paidBy: this.paidBy(),
        returnCharge: this.charge(),
        restock: this.restock(),
        note: this.note.trim() || null,
      })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.toast.success('Return recorded');
          void this.router.navigate(['/orders', o.orderId]);
        },
        error: (err: unknown) => {
          this.saving.set(false);
          this.toast.error(errorMessage(err));
        },
      });
  }

  protected variant(color?: string, size?: string): string {
    return [color, size].filter(Boolean).join(' / ');
  }
}

function round(value: number): number {
  return Math.round(value * 100) / 100;
}
