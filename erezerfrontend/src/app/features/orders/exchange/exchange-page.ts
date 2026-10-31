import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { errorMessage } from '../../../core/http/api-error';
import { ToastService } from '../../../core/notify/toast.service';
import { BdDatePipe } from '../../../shared/ui/bd-date.pipe';
import { VariantPicker } from '../../stock/variant-picker';
import { SizeField } from '../../stock/size-field';
import { SoldProductsApi } from '../../sold-products/sold-products.api';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, of } from 'rxjs';
import { TakaPipe } from '../../../shared/ui/taka.pipe';
import { OrderApi } from '../data/order.api';
import { EXCHANGE_REASONS } from '../data/order-labels';
import {
  imageDescriptions,
  DeliveryPaidBy,
  ExchangeableItem,
  ExchangeCandidate,
  ExchangeOptions,
  ExchangeReason,
  OrderImage,
} from '../data/order.models';

const MAX_IMAGES = 10;

/** One original line item and, when chosen, the replacement that ships instead. */
interface Line {
  item: ExchangeableItem;
  selected: boolean;
  quantity: number;
  productName: string;
  productCode: string;
  color: string;
  /** Stock variant of the replacement (starts as the original's). */
  variantId: number | null;
  /** The replacement's size (stock item); starts as the original's. */
  stockItemId: number | null;
  size: string;
  description: string;
  unitPrice: number | null;
  images: OrderImage[];
  uploading: number;
}

/**
 * Exchange items of a delivered order: find the order, choose which items come back and what ships instead, then
 * the summary works out the price difference, who pays the delivery and what the rider collects.
 */
@Component({
  selector: 'app-exchange-page',
  imports: [FormsModule, RouterLink, TakaPipe, BdDatePipe, VariantPicker, SizeField],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './exchange-page.html',
})
export class ExchangePage implements OnInit {
  private readonly api = inject(OrderApi);
  private readonly toast = inject(ToastService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  protected readonly productNames = toSignal(inject(SoldProductsApi).productNames().pipe(catchError(() => of([] as string[]))), { initialValue: [] as string[] });

  protected readonly reasons = EXCHANGE_REASONS;

  // Step 1: find the order
  protected query = '';
  protected readonly searching = signal(false);
  protected readonly searched = signal(false);
  protected readonly candidates = signal<ExchangeCandidate[]>([]);

  // Step 2: the exchange
  protected readonly loading = signal(false);
  protected readonly options = signal<ExchangeOptions | null>(null);
  protected readonly lines = signal<Line[]>([]);
  protected readonly reason = signal<ExchangeReason>('WRONG_SIZE');
  protected readonly paidBy = signal<DeliveryPaidBy>('CUSTOMER');
  protected readonly deliveryCharge = signal<number | null>(0);
  protected readonly refund = signal(true);
  protected note = '';
  /** Optional, like Add Order; generated when empty. */
  protected orderNumber = '';
  protected readonly saving = signal(false);

  protected readonly selected = computed(() => this.lines().filter((l) => l.selected));
  protected readonly credit = computed(() =>
    round(this.selected().reduce((sum, l) => sum + l.item.unitCredit * l.quantity, 0)),
  );
  protected readonly newItems = computed(() =>
    round(this.selected().reduce((sum, l) => sum + (Number(l.unitPrice) || 0) * l.quantity, 0)),
  );
  protected readonly difference = computed(() => round(this.newItems() - this.credit()));
  protected readonly delivery = computed(() => Math.max(Number(this.deliveryCharge()) || 0, 0));
  /** What the rider collects: a positive price difference, plus the delivery when the customer pays it. */
  protected readonly collect = computed(
    () => round(Math.max(this.difference(), 0) + (this.paidBy() === 'CUSTOMER' ? this.delivery() : 0)),
  );
  protected readonly refundAmount = computed(() =>
    this.difference() < 0 && this.refund() ? -this.difference() : 0,
  );
  protected readonly uploadingAny = computed(() => this.lines().some((l) => l.uploading > 0));
  protected readonly problems = computed(() => {
    const o = this.options();
    const result: string[] = [];
    if (!o) return result;
    if (!this.selected().length) result.push('Choose at least one item to exchange');
    for (const l of this.selected()) {
      if (!l.productName.trim()) result.push(`${l.item.productName}: the new product needs a name`);
      if (l.unitPrice === null || Number(l.unitPrice) < 0 || `${l.unitPrice}` === '') result.push(`${l.item.productName}: enter the new price`);
      if (!(l.quantity >= 1 && l.quantity <= l.item.remaining)) result.push(`${l.item.productName}: quantity must be 1–${l.item.remaining}`);
      if (l.color.trim() && l.variantId === null) result.push(`${l.item.productName}: choose the variant from the list`);
      if (l.variantId !== null && l.stockItemId === null) result.push(`${l.item.productName}: choose the size`);
    }
    if (this.deliveryCharge() === null || Number(this.deliveryCharge()) < 0) result.push('Enter the exchange delivery charge (0 if none)');
    if (!/^[A-Za-z0-9-]{0,30}$/.test(this.orderNumber.trim())) result.push('Order ID: letters, digits and dashes only (max 30)');
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
    this.api.exchangeOptions(orderId).subscribe({
      next: (o) => {
        this.loading.set(false);
        this.options.set(o);
        this.deliveryCharge.set(o.defaultDeliveryCharge);
        // The replacement starts as a copy of the original, so a size change is a single edit.
        this.lines.set(
          o.items.map((item) => ({
            item,
            selected: false,
            quantity: Math.min(1, item.remaining),
            productName: item.productName,
            productCode: item.productCode ?? '',
            color: item.color ?? '',
            variantId: item.variantId ?? null,
            stockItemId: item.stockItemId ?? null,
            size: item.size ?? '',
            description: item.description ?? '',
            unitPrice: item.unitPrice,
            images: [...item.images],
            uploading: 0,
          })),
        );
        void this.router.navigate([], { queryParams: { order: orderId }, replaceUrl: true });
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
    this.candidates.set([]);
    this.searched.set(false);
    void this.router.navigate([], { queryParams: {}, replaceUrl: true });
  }

  protected update(line: Line, patch: Partial<Line>): void {
    this.lines.update((lines) => lines.map((l) => (l === line ? { ...l, ...patch } : l)));
  }

  protected setReason(reason: ExchangeReason): void {
    this.reason.set(reason);
    // The reason suggests who pays; it can still be changed.
    this.paidBy.set(EXCHANGE_REASONS.find((r) => r.value === reason)?.suggestedPayer ?? 'CUSTOMER');
  }

  protected removeImage(line: Line, image: OrderImage): void {
    this.update(line, { images: line.images.filter((i) => i !== image) });
  }

  protected onFiles(line: Line, input: HTMLInputElement): void {
    const files = Array.from(input.files ?? []);
    input.value = '';
    const room = MAX_IMAGES - line.images.length;
    if (!files.length || room <= 0) {
      if (room <= 0) this.toast.info(`At most ${MAX_IMAGES} images per product`);
      return;
    }
    const batch = files.slice(0, room);
    this.adjustUploading(line.item.id, 1);
    this.api.uploadImages(batch).subscribe({
      next: (uploaded) => {
        this.adjustUploading(line.item.id, -1, uploaded.map((f) => ({ fileId: f.id, url: f.url })));
      },
      error: (err: unknown) => {
        this.adjustUploading(line.item.id, -1);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected submit(): void {
    const o = this.options();
    if (!o || this.problems().length || this.uploadingAny()) return;
    this.saving.set(true);
    this.api
      .createExchange(o.orderId, {
        items: this.selected().map((l) => ({
          originalItemId: l.item.id,
          quantity: l.quantity,
          replacement: {
            productName: l.productName.trim(),
            productCode: l.productCode.trim() || null,
            description: l.description.trim() || null,
            color: l.color.trim() || null,
            size: l.size.trim() || null,
            unitPrice: Number(l.unitPrice),
            quantity: l.quantity,
            imageIds: l.images.map((i) => i.fileId),
            imageDescriptions: imageDescriptions(l.images),
            variantId: l.variantId,
            stockItemId: l.variantId === null ? null : l.stockItemId,
          },
        })),
        reason: this.reason(),
        deliveryPaidBy: this.paidBy(),
        deliveryCharge: this.delivery(),
        refund: this.refund(),
        note: this.note.trim() || null,
        orderNumber: this.orderNumber.trim().toUpperCase() || null,
      })
      .subscribe({
        next: ({ id }) => {
          this.saving.set(false);
          this.toast.success('Exchange created');
          void this.router.navigate(['/orders', id]);
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

  private adjustUploading(itemId: number, delta: number, added: OrderImage[] = []): void {
    this.lines.update((lines) =>
      lines.map((l) =>
        l.item.id === itemId
          ? { ...l, uploading: Math.max(l.uploading + delta, 0), images: [...l.images, ...added].slice(0, MAX_IMAGES) }
          : l,
      ),
    );
  }
}

function round(value: number): number {
  return Math.round(value * 100) / 100;
}
