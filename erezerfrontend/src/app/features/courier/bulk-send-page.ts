import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { errorMessage } from '../../core/http/api-error';
import { ToastService } from '../../core/notify/toast.service';
import { ConfirmService } from '../../shared/ui/confirm-dialog';
import { TakaPipe } from '../../shared/ui/taka.pipe';
import { PAYMENT_LABELS } from '../orders/data/order-labels';
import { BatchView, CourierApi, ParcelPreview, isValidCod } from './courier.api';

interface Row {
  parcel: ParcelPreview;
  include: boolean;
  cod: number;
}

/**
 * Bulk send: preview every selected order (ready / needs fixing / not eligible), adjust COD, send in the
 * background and watch progress, then retry failures.
 */
@Component({
  selector: 'app-bulk-send-page',
  imports: [FormsModule, RouterLink, TakaPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './bulk-send-page.html',
})
export class BulkSendPage implements OnInit {
  private readonly api = inject(CourierApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);

  protected readonly paymentLabels = PAYMENT_LABELS;
  protected readonly loading = signal(true);
  protected readonly rows = signal<Row[]>([]);
  protected readonly starting = signal(false);
  protected readonly batch = signal<BatchView | null>(null);

  protected readonly readyRows = computed(() => this.rows().filter((r) => r.parcel.ready));
  protected readonly toSend = computed(() => this.rows().filter((r) => r.include && r.parcel.ready && this.validCod(r.cod)));
  protected readonly totalCod = computed(() => this.toSend().reduce((sum, r) => sum + Number(r.cod), 0));
  protected readonly blockedCount = computed(() => this.rows().length - this.readyRows().length);
  protected readonly progress = computed(() => {
    const b = this.batch();
    return b ? Math.round((b.processed / Math.max(b.total, 1)) * 100) : 0;
  });

  private pollTimer: ReturnType<typeof setTimeout> | undefined;

  constructor() {
    inject(DestroyRef).onDestroy(() => clearTimeout(this.pollTimer));
  }

  ngOnInit(): void {
    const ids = (this.route.snapshot.queryParamMap.get('ids') ?? '')
      .split(',').map(Number).filter((n) => Number.isInteger(n) && n > 0);
    if (!ids.length) {
      void this.router.navigate(['/orders']);
      return;
    }
    this.api.preview(ids.slice(0, 500)).subscribe({
      next: (r) => {
        this.rows.set(r.parcels.map((parcel) => ({ parcel, include: parcel.ready, cod: parcel.codAmount })));
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.toast.error(errorMessage(err));
        void this.router.navigate(['/orders']);
      },
    });
  }

  protected validCod(cod: number): boolean {
    return isValidCod(cod);
  }

  protected toggleAll(include: boolean): void {
    this.rows.update((rows) => rows.map((r) => ({ ...r, include: r.parcel.ready && include })));
  }

  protected setInclude(row: Row, include: boolean): void {
    this.rows.update((rows) => rows.map((r) => (r === row ? { ...r, include } : r)));
  }

  protected setCod(row: Row, cod: number): void {
    this.rows.update((rows) => rows.map((r) => (r === row ? { ...r, cod } : r)));
  }

  protected async send(): Promise<void> {
    const items = this.toSend();
    const ok = await this.confirm.ask({
      title: `Send ${items.length} parcel${items.length === 1 ? '' : 's'} to Steadfast?`,
      message: `Steadfast will collect ৳ ${this.totalCod().toLocaleString('en-US')} in total. Each order moves to Shipped as it is booked.`,
      confirmLabel: 'Send now',
    });
    if (!ok) {
      return;
    }
    this.starting.set(true);
    this.api.startBatch(items.map((r) => ({ orderId: r.parcel.orderId, codAmount: Number(r.cod) }))).subscribe({
      next: ({ batchId }) => {
        this.starting.set(false);
        this.poll(batchId);
      },
      error: (err: unknown) => {
        this.starting.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected retryFailed(): void {
    const failed = this.batch()?.items.filter((i) => i.status === 'FAILED').map((i) => i.orderId) ?? [];
    void this.router.navigate(['/orders/send-to-courier'], { queryParams: { ids: failed.join(',') } }).then(() => {
      this.batch.set(null);
      this.loading.set(true);
      this.ngOnInit();
    });
  }

  private poll(batchId: number): void {
    this.api.batch(batchId).subscribe({
      next: (b) => {
        this.batch.set(b);
        if (b.status === 'RUNNING') {
          this.pollTimer = setTimeout(() => this.poll(batchId), 1000);
        } else if (b.failed === 0) {
          this.toast.success(`All ${b.succeeded} parcels were sent to Steadfast`);
        } else {
          this.toast.error(`${b.failed} of ${b.total} parcels could not be sent; see the reasons below`);
        }
      },
      error: () => (this.pollTimer = setTimeout(() => this.poll(batchId), 3000)),
    });
  }
}
