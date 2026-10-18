import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, HostListener, inject, input, OnInit, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { errorMessage } from '../../core/http/api-error';
import { ToastService } from '../../core/notify/toast.service';
import { TakaPipe } from '../../shared/ui/taka.pipe';
import { PAYMENT_LABELS } from '../orders/data/order-labels';
import { CourierApi, DELIVERY_TYPES, FraudReport, isValidCod, ParcelPreview, SendResult } from './courier.api';

/** Preview of exactly what Steadfast will receive for one order, with editable fields, then send. */
@Component({
  selector: 'app-send-to-courier-dialog',
  imports: [DecimalPipe, FormsModule, TakaPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="fixed inset-0 z-[85] flex items-start justify-center overflow-y-auto bg-black/60 p-4 sm:items-center" (click)="close()">
      <div class="card w-full max-w-2xl shadow-2xl" role="dialog" aria-modal="true" aria-label="Send to Steadfast" (click)="$event.stopPropagation()">
        <div class="flex items-center justify-between border-b border-line px-6 py-4">
          <div>
            <h2 class="text-lg font-semibold text-white"><i class="fas fa-shipping-fast mr-2 text-brand-light"></i>Send to Steadfast</h2>
            @if (parcel(); as p) { <p class="text-xs text-slate-400">Order {{ p.orderNumber }} · check the details, then send</p> }
          </div>
          <button type="button" class="cursor-pointer text-slate-400 hover:text-white" (click)="close()" aria-label="Close"><i class="fas fa-times"></i></button>
        </div>

        @if (parcel(); as p) {
          <div class="space-y-5 px-6 py-5">
            @if (p.blockedReason) {
              <div class="rounded-lg border border-red-800 bg-red-950/50 px-4 py-3 text-sm text-red-200" role="alert"><i class="fas fa-ban mr-1"></i>{{ p.blockedReason }}</div>
            }
            @for (problem of p.problems; track problem) {
              <div class="rounded-lg border border-red-800 bg-red-950/50 px-4 py-2 text-sm text-red-200" role="alert"><i class="fas fa-exclamation-circle mr-1"></i>{{ problem }}</div>
            }
            @if (p.customerHistory; as h) {
              @if (h.returned > 0) {
                <div class="rounded-lg border border-amber-600/60 bg-amber-950/40 px-4 py-3 text-sm text-amber-200">
                  <i class="fas fa-exclamation-triangle mr-1"></i>
                  This customer returned {{ h.returned }} of {{ h.delivered + h.returned }} parcel{{ h.delivered + h.returned === 1 ? '' : 's' }}.
                  Consider collecting the delivery charge in advance (lower the COD amount accordingly).
                </div>
              }
            }

            <!-- Steadfast's record of this phone across all merchants. On demand: Steadfast limits checks per account. -->
            <div class="rounded-lg border border-line bg-app/50 px-4 py-3 text-sm">
              @if (fraud(); as f) {
                <div class="flex flex-wrap items-baseline justify-between gap-2">
                  <p class="font-semibold text-white"><i class="fas fa-user-shield mr-1 text-brand-light"></i>Steadfast delivery record</p>
                  @if (f.volumeRange) { <p class="text-xs text-slate-400">{{ f.volumeRange }} parcels with Steadfast merchants</p> }
                </div>
                <dl class="mt-2 grid grid-cols-2 gap-3 sm:grid-cols-4">
                  <div><dt class="text-xs text-slate-400">Delivered</dt>
                    <dd class="font-semibold" [class]="ratioClass(f.deliveryRatio, true)">{{ f.deliveryRatio == null ? 'Unknown' : (f.deliveryRatio * 100 | number: '1.0-0') + '%' }}</dd></div>
                  <div><dt class="text-xs text-slate-400">Cancelled</dt>
                    <dd class="font-semibold" [class]="ratioClass(f.cancellationRatio, false)">{{ f.cancellationRatio == null ? 'Unknown' : (f.cancellationRatio * 100 | number: '1.0-0') + '%' }}</dd></div>
                  <div><dt class="text-xs text-slate-400">Parcels</dt>
                    <dd class="text-white">{{ f.deliveredCount ?? '—' }} delivered · {{ f.cancelledCount ?? '—' }} cancelled</dd></div>
                  <div><dt class="text-xs text-slate-400">Fraud reports</dt>
                    <dd [class]="f.totalReports ? 'font-semibold text-red-300' : 'text-white'">{{ f.totalReports ?? 0 }}</dd></div>
                </dl>
                @if (f.fraudCategories.length) {
                  <p class="mt-2 text-xs text-red-300"><i class="fas fa-flag mr-1"></i>Reported for: {{ f.fraudCategories.join(', ') }}</p>
                }
                @if (f.deliveryRatio == null) {
                  <p class="mt-2 text-xs text-slate-500">Unknown means Steadfast has too little history for this number, not that it is safe.</p>
                }
              } @else {
                <div class="flex flex-wrap items-center justify-between gap-2">
                  <p class="text-slate-400"><i class="fas fa-user-shield mr-1"></i>Check this customer's delivery record with all Steadfast merchants.</p>
                  <button type="button" class="btn-secondary" (click)="checkFraud(p)" [disabled]="checking()">
                    @if (checking()) { <i class="fas fa-circle-notch fa-spin"></i> } @else { <i class="fas fa-search"></i> } Check record
                  </button>
                </div>
                @if (fraudError()) { <p class="mt-2 text-xs text-amber-300" role="alert">{{ fraudError() }}</p> }
              }
            </div>

            <dl class="grid grid-cols-1 gap-x-6 gap-y-3 rounded-lg border border-line bg-app/50 p-4 text-sm sm:grid-cols-2">
              <div><dt class="text-xs text-slate-400">Recipient</dt><dd class="font-semibold text-white">{{ p.recipientName }}</dd></div>
              <div><dt class="text-xs text-slate-400">Phone</dt><dd class="font-mono text-white">{{ p.recipientPhone }}</dd></div>
              <div class="sm:col-span-2"><dt class="text-xs text-slate-400">Address</dt><dd class="text-white">{{ p.recipientAddress }}</dd></div>
              <div><dt class="text-xs text-slate-400">Invoice (order number)</dt><dd class="font-mono text-white">{{ p.orderNumber }}</dd></div>
              <div><dt class="text-xs text-slate-400">Order total · payment</dt><dd class="text-white">{{ p.orderTotal | taka }} · {{ paymentLabels[p.paymentMethod] }}</dd></div>
            </dl>

            <div class="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <label class="block">
                <span class="field-label">Cash to collect (COD) *</span>
                <input type="number" min="0" step="0.01" class="field text-lg font-semibold" [(ngModel)]="cod" aria-label="COD amount" />
                <span class="mt-1 block text-xs text-slate-500">
                  Default {{ p.defaultCodAmount | taka }} ({{ p.paymentMethod === 'CASH' ? 'cash on delivery: full total' : 'prepaid: nothing to collect' }}). Up to 2 decimal places.
                </span>
              </label>
              <label class="block">
                <span class="field-label">Delivery type</span>
                <select class="field" [(ngModel)]="deliveryType" aria-label="Delivery type">
                  @for (t of deliveryTypes; track t.value) { <option [ngValue]="t.value">{{ t.label }}</option> }
                </select>
              </label>
              <label class="block sm:col-span-2">
                <span class="field-label">Item description</span>
                <input class="field" maxlength="255" [(ngModel)]="itemDescription" aria-label="Item description" />
              </label>
              <label class="block sm:col-span-2">
                <span class="field-label">Note for the rider</span>
                <textarea rows="2" class="field" maxlength="480" [(ngModel)]="note" placeholder="e.g. Call before delivery" aria-label="Note"></textarea>
              </label>
            </div>
          </div>
          <div class="flex items-center justify-between gap-3 border-t border-line px-6 py-4">
            <p class="text-xs text-slate-500">The order moves to <b>Shipped</b> once Steadfast confirms.</p>
            <div class="flex gap-2">
              <button type="button" class="btn-secondary" (click)="close()">Cancel</button>
              <button type="button" class="btn-primary" [disabled]="sending() || !!p.blockedReason || p.problems.length > 0 || !validCod()" (click)="send(p)">
                @if (sending()) { <i class="fas fa-circle-notch fa-spin"></i> Sending… } @else { <i class="fas fa-paper-plane"></i> Send to Steadfast }
              </button>
            </div>
          </div>
        } @else {
          <div class="flex justify-center py-16 text-slate-400"><i class="fas fa-circle-notch fa-spin text-2xl"></i></div>
        }
      </div>
    </div>
  `,
})
export class SendToCourierDialog implements OnInit {
  readonly orderId = input.required<number>();
  readonly closed = output<SendResult | null>();

  private readonly api = inject(CourierApi);
  private readonly toast = inject(ToastService);

  protected readonly deliveryTypes = DELIVERY_TYPES;
  protected readonly paymentLabels = PAYMENT_LABELS;
  protected readonly parcel = signal<ParcelPreview | null>(null);
  protected readonly sending = signal(false);
  protected readonly fraud = signal<FraudReport | null>(null);
  protected readonly checking = signal(false);
  protected readonly fraudError = signal<string | null>(null);

  protected cod: number | null = null;
  protected deliveryType = 0;
  protected itemDescription = '';
  protected note = '';

  ngOnInit(): void {
    this.api.preview([this.orderId()]).subscribe({
      next: (r) => {
        const p = r.parcels[0];
        this.parcel.set(p);
        this.cod = p.codAmount;
        this.deliveryType = p.deliveryType;
        this.itemDescription = p.itemDescription ?? '';
        this.note = p.note ?? '';
      },
      error: (err: unknown) => {
        this.toast.error(errorMessage(err));
        this.closed.emit(null);
      },
    });
  }

  protected validCod(): boolean {
    return isValidCod(this.cod);
  }

  protected checkFraud(p: ParcelPreview): void {
    this.checking.set(true);
    this.fraudError.set(null);
    this.api.fraudCheck(p.recipientPhone).subscribe({
      next: (report) => {
        this.checking.set(false);
        this.fraud.set(report);
      },
      error: (err: unknown) => {
        // Not fatal: the parcel can still be sent (e.g. the account's check limit is used up).
        this.checking.set(false);
        this.fraudError.set(errorMessage(err));
      },
    });
  }

  /** Green/amber/red by Steadfast's common thresholds; unknown stays neutral. */
  protected ratioClass(ratio: number | null | undefined, higherIsBetter: boolean): string {
    if (ratio == null) return 'text-slate-300';
    const good = higherIsBetter ? ratio >= 0.8 : ratio <= 0.1;
    const bad = higherIsBetter ? ratio < 0.5 : ratio > 0.3;
    return good ? 'text-emerald-300' : bad ? 'text-red-300' : 'text-amber-300';
  }

  protected send(p: ParcelPreview): void {
    this.sending.set(true);
    this.api
      .send(p.orderId, {
        codAmount: Number(this.cod),
        deliveryType: this.deliveryType,
        note: this.note.trim() || null,
        itemDescription: this.itemDescription.trim() || null,
      })
      .subscribe({
        next: (result) => {
          this.sending.set(false);
          if (result.state === 'BOOKED') {
            const ref = result.shipment.trackingCode ?? result.shipment.consignmentId;
            this.toast.success(ref ? `Sent to Steadfast · tracking ${ref}` : result.message);
          } else {
            this.toast.info(result.message);
          }
          this.closed.emit(result);
        },
        error: (err: unknown) => {
          this.sending.set(false);
          this.toast.error(errorMessage(err));
        },
      });
  }

  @HostListener('document:keydown.escape')
  protected close(): void {
    if (!this.sending()) {
      this.closed.emit(null);
    }
  }
}
