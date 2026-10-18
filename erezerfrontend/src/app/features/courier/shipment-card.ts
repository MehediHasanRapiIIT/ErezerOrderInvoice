import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { TakaPipe } from '../../shared/ui/taka.pipe';
import { COURIER_STATUS_LABELS, DELIVERY_TYPES, Shipment } from './courier.api';
import { BdDatePipe } from '../../shared/ui/bd-date.pipe';

/** The courier booking on the order page: tracking, Steadfast's status and the real courier charge. */
@Component({
  selector: 'app-shipment-card',
  imports: [BdDatePipe, TakaPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let s = shipment();
    <section class="card p-5">
      <div class="mb-3 flex items-center justify-between">
        <h2 class="font-semibold text-white"><i class="fas fa-shipping-fast mr-2 text-brand-light"></i>Steadfast</h2>
        @if (canRefresh()) {
          <button type="button" class="cursor-pointer rounded px-2 py-1 text-xs text-slate-300 hover:bg-white/5" (click)="refresh.emit()" [disabled]="refreshing()">
            <i class="fas fa-sync-alt mr-1" [class.fa-spin]="refreshing()"></i>Refresh status
          </button>
        }
      </div>

      @if (s.state === 'CONFIRMING') {
        <p class="rounded-md border border-amber-600/60 bg-amber-950/40 px-3 py-2 text-sm text-amber-200">
          <i class="fas fa-hourglass-half mr-1"></i>Waiting for Steadfast to confirm the booking. Don't send this order again;
          it is confirmed automatically within a few minutes.
        </p>
      } @else {
        @if (s.needsAttention) {
          <p class="mb-3 rounded-md border border-amber-600/60 bg-amber-950/40 px-3 py-2 text-sm text-amber-200" role="alert">
            <i class="fas fa-exclamation-triangle mr-1"></i>Needs your attention: Steadfast reports
            <b>{{ statusLabel() }}</b>. Check the parcel in the Steadfast panel and update the order if needed.
          </p>
        }
        <dl class="space-y-2 text-sm">
          <div class="flex justify-between gap-3"><dt class="text-slate-400">Status</dt>
            <dd class="text-right font-semibold text-white">{{ statusLabel() }}</dd></div>
          @if (s.trackingMessage) {
            <div class="flex justify-between gap-3"><dt class="text-slate-400">Latest update</dt><dd class="text-right text-slate-200">{{ s.trackingMessage }}</dd></div>
          }
          <div class="flex justify-between gap-3"><dt class="text-slate-400">Tracking</dt>
            <dd class="text-right font-mono text-white">
              @if (s.trackingLink) {
                <a [href]="s.trackingLink" target="_blank" rel="noopener" class="text-brand-light hover:underline">{{ s.trackingCode }} <i class="fas fa-external-link-alt text-[10px]"></i></a>
              } @else {
                {{ s.trackingCode ?? '—' }}
              }
            </dd></div>
          <div class="flex justify-between gap-3"><dt class="text-slate-400">Consignment</dt><dd class="font-mono text-slate-200">{{ s.consignmentId ?? 'pending' }}</dd></div>
          <div class="flex justify-between gap-3"><dt class="text-slate-400">COD to collect</dt><dd class="text-white">{{ s.codAmount | taka }}</dd></div>
          <div class="flex justify-between gap-3"><dt class="text-slate-400">Delivery</dt><dd class="text-slate-200">{{ deliveryLabel() }}</dd></div>
          @if (s.deliveryCharge != null) {
            <div class="flex justify-between gap-3"><dt class="text-slate-400">Courier charge</dt><dd class="text-white">{{ s.deliveryCharge | taka }}</dd></div>
          }
          @if (s.courierStatusAt) {
            <div class="flex justify-between gap-3"><dt class="text-slate-400">Updated</dt><dd class="text-slate-400">{{ s.courierStatusAt | bdDate: 'd MMM y, h:mm a' }}</dd></div>
          }
        </dl>
      }
      @if (s.events.length) {
        <details class="mt-3 border-t border-line pt-3 text-xs">
          <summary class="cursor-pointer text-slate-400 hover:text-white">Courier updates ({{ s.events.length }})</summary>
          <ul class="mt-2 space-y-1">
            @for (e of s.events; track $index) {
              <li class="flex justify-between gap-2 text-slate-400">
                <span>{{ eventLabel(e.courierStatus) }} <span class="text-slate-600">· {{ e.source.toLowerCase() }}</span></span>
                <span>{{ e.receivedAt | bdDate: 'd MMM, h:mm a' }}</span>
              </li>
            }
          </ul>
        </details>
      }
    </section>
  `,
})
export class ShipmentCard {
  readonly shipment = input.required<Shipment>();
  readonly canRefresh = input(false);
  readonly refreshing = input(false);
  readonly refresh = output<void>();

  protected eventLabel(status?: string): string {
    return status ? (COURIER_STATUS_LABELS[status] ?? status) : '—';
  }
  protected readonly statusLabel = computed(() => {
    const status = this.shipment().courierStatus;
    return status ? (COURIER_STATUS_LABELS[status] ?? status) : 'Booked';
  });
  protected readonly deliveryLabel = computed(
    () => DELIVERY_TYPES.find((t) => t.value === this.shipment().deliveryType)?.label ?? '—',
  );
}
