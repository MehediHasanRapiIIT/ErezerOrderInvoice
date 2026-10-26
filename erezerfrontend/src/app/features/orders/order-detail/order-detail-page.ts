import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule, NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { errorMessage, toProblem } from '../../../core/http/api-error';
import { saveBlob, showBlobInTab } from '../../../core/http/file-download';
import { ToastService } from '../../../core/notify/toast.service';
import { HasPermissionDirective } from '../../../shared/has-permission.directive';
import { ConfirmService } from '../../../shared/ui/confirm-dialog';
import { TakaPipe } from '../../../shared/ui/taka.pipe';
import { OrderApi } from '../data/order.api';
import { PAYMENT_LABELS, RETURN_REASONS, STATUS_FLOW, STATUS_LABELS } from '../data/order-labels';
import { DeliveryPaidBy, OrderDetail, OrderImage, OrderStatus, ReturnReason, ReturnView } from '../data/order.models';
import { StatusBadge } from '../status-badge';
import { CourierApi, SENDABLE_STATUSES, SendResult } from '../../courier/courier.api';
import { SendToCourierDialog } from '../../courier/send-to-courier-dialog';
import { ShipmentCard } from '../../courier/shipment-card';
import { BdDatePipe } from '../../../shared/ui/bd-date.pipe';
import { DateTimePicker } from '../../../shared/ui/date-time-picker';
import { fromBdInputValue, toBdInputValue } from '../../../shared/bd-datetime-input';

export interface TimelineStep {
  status: OrderStatus;
  label: string;
  state: 'done' | 'current' | 'upcoming' | 'failed';
  at?: string;
}

@Component({
  selector: 'app-order-detail-page',
  imports: [BdDatePipe, FormsModule, ReactiveFormsModule, RouterLink, HasPermissionDirective, StatusBadge, TakaPipe, SendToCourierDialog, ShipmentCard, DateTimePicker],
  templateUrl: './order-detail-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderDetailPage {
  /** Bound from the :id route parameter. */
  readonly id = input.required<string>();

  private readonly api = inject(OrderApi);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);
  private readonly fb = inject(NonNullableFormBuilder);
  protected readonly auth = inject(AuthService);

  protected readonly paymentLabels = PAYMENT_LABELS;
  protected readonly statusLabels = STATUS_LABELS;

  protected readonly resource = rxResource({
    params: () => Number(this.id()),
    stream: ({ params }) => this.api.get(params),
  });

  /** The latest order, from the initial load or from an action's response. */
  protected readonly order = computed(() => (this.resource.hasValue() ? this.resource.value() : undefined));
  protected readonly notFound = computed(() => toProblem(this.resource.error()).status === 404);

  protected readonly itemCount = computed(() => this.order()?.items.reduce((n, i) => n + i.quantity, 0) ?? 0);

  protected readonly timeline = computed<TimelineStep[]>(() => {
    const order = this.order();
    return order ? buildTimeline(order) : [];
  });

  protected readonly lightbox = signal<{ images: OrderImage[]; index: number } | null>(null);

  // ---- Courier (Steadfast) ----
  private readonly courierApi = inject(CourierApi);
  protected readonly courierAvailability = rxResource({ stream: () => this.courierApi.availability() });
  protected readonly shipmentResource = rxResource({
    params: () => Number(this.id()),
    stream: ({ params }) => this.courierApi.shipment(params),
  });
  protected readonly shipment = computed(() => (this.shipmentResource.hasValue() ? this.shipmentResource.value() : null));
  protected readonly showSendDialog = signal(false);
  protected readonly refreshingShipment = signal(false);
  /** Whether the "Send to Steadfast" button appears at all (integration on, permission, no booking yet). */
  protected readonly courierActionVisible = computed(() =>
    !!this.order() && !this.shipment() && this.shipmentResource.hasValue() && this.auth.hasPermission('COURIER_SEND')
    && this.courierAvailability.hasValue() && this.courierAvailability.value().enabled
    && (SENDABLE_STATUSES.includes(this.order()!.status) || this.order()!.status === 'PLACED'));
  protected readonly courierSendable = computed(() => !!this.order() && SENDABLE_STATUSES.includes(this.order()!.status));
  protected readonly savingStatus = signal(false);
  protected readonly savingNote = signal(false);
  protected readonly pdfBusy = signal(false);

  protected readonly statusForm = this.fb.group({
    status: this.fb.control<OrderStatus | ''>('', Validators.required),
    courier: [''],
    trackingNumber: [''],
    returnDeliveryCharge: this.fb.control<number | null>(null, Validators.min(0)),
    /** No default: who pays the return charge is the admin's choice each time. */
    returnPaidBy: this.fb.control<DeliveryPaidBy | ''>(''),
    returnReason: this.fb.control<ReturnReason>('REFUSED'),
    note: ['', Validators.maxLength(1000)],
  });

  protected readonly returnReasons = RETURN_REASONS;

  protected readonly noteForm = this.fb.group({ body: ['', [Validators.required, Validators.maxLength(2000)]] });

  private readonly selectedStatus = signal<OrderStatus | ''>('');

  protected readonly needsShipment = computed(() => this.selectedStatus() === 'SHIPPED');
  protected readonly isReturn = computed(() => this.selectedStatus() === 'RETURNED');

  constructor() {
    // Preselect the next step of the flow whenever the order changes.
    effect(() => {
      const order = this.order();
      // Preselect the normal next step (not simply the first status, which for free changes would be "Placed").
      const flowNext = order ? STATUS_FLOW[STATUS_FLOW.indexOf(order.status) + 1] : undefined;
      const next = order ? (flowNext && order.allowedNextStatuses.includes(flowNext) ? flowNext : order.allowedNextStatuses[0] ?? '') : '';
      this.statusForm.reset({
        status: next,
        courier: this.order()?.courier ?? '',
        trackingNumber: this.order()?.trackingNumber ?? '',
        returnReason: order?.status === 'DELIVERED' ? 'DID_NOT_LIKE' : 'REFUSED',
      });
      this.selectedStatus.set(next);
    });
    this.statusForm.controls.status.valueChanges.subscribe((value) => this.selectedStatus.set(value));
  }

  protected async submitStatus(): Promise<void> {
    const order = this.order();
    const value = this.statusForm.getRawValue();
    if (!order || !value.status) {
      return;
    }
    if (value.status === 'SHIPPED' && (!value.courier.trim() || !value.trackingNumber.trim())) {
      this.toast.error('Courier and tracking number are required to mark the order as shipped');
      return;
    }
    if (value.status === 'RETURNED' && !value.returnPaidBy) {
      this.toast.error('Choose who pays the return charge: Erezer or the customer');
      return;
    }
    const backwards = STATUS_FLOW.includes(value.status) && STATUS_FLOW.includes(order.status)
      && STATUS_FLOW.indexOf(value.status) < STATUS_FLOW.indexOf(order.status);
    const reopening = order.status === 'CANCELLED' || order.status === 'RETURNED';
    if (order.freeStatusChange && (backwards || reopening) && this.shipment()) {
      const confirmed = await this.confirm.ask({
        title: 'This order is booked with Steadfast',
        message: `Changing it to ${STATUS_LABELS[value.status].toLowerCase()} here does not change the parcel at Steadfast, and Steadfast's own updates may change the status again later. Continue?`,
        confirmLabel: 'Change anyway',
        danger: true,
      });
      if (!confirmed) {
        return;
      }
    } else if (value.status === 'CANCELLED' || value.status === 'RETURNED') {
      const confirmed = await this.confirm.ask({
        title: `Mark order as ${STATUS_LABELS[value.status].toLowerCase()}?`,
        message: order.freeStatusChange
          ? 'Stock is given back. You can still change the status again later.'
          : 'Stock is given back, and you will not be able to change this order\'s status afterwards.',
        confirmLabel: `Mark ${STATUS_LABELS[value.status].toLowerCase()}`,
        danger: true,
      });
      if (!confirmed) {
        return;
      }
    }

    this.savingStatus.set(true);
    this.api
      .changeStatus(order.id, {
        status: value.status,
        courier: value.courier.trim() || null,
        trackingNumber: value.trackingNumber.trim() || null,
        note: value.note.trim() || null,
        returnDeliveryCharge: value.status === 'RETURNED' ? value.returnDeliveryCharge : null,
        returnPaidBy: value.status === 'RETURNED' ? value.returnPaidBy || null : null,
        returnReason: value.status === 'RETURNED' ? value.returnReason : null,
      })
      .subscribe({
        next: (updated) => {
          this.resource.set(updated);
          this.toast.success(`Order marked as ${STATUS_LABELS[updated.status].toLowerCase()}`);
          this.savingStatus.set(false);
        },
        error: (err: unknown) => {
          this.toast.error(errorMessage(err));
          this.savingStatus.set(false);
        },
      });
  }

  // Correcting a return afterwards
  protected readonly editingReturn = signal<number | null>(null);
  protected readonly savingReturn = signal(false);
  protected returnEdit: { reason: ReturnReason; paidBy: DeliveryPaidBy; returnCharge: number | null; note: string } = {
    reason: 'OTHER',
    paidBy: 'EREZER',
    returnCharge: 0,
    note: '',
  };

  protected readonly activeReturns = computed(() => (this.order()?.returns ?? []).filter((r) => r.active));

  protected editReturn(r: ReturnView): void {
    this.returnEdit = { reason: r.reason, paidBy: r.paidBy, returnCharge: r.returnCharge, note: r.note ?? '' };
    this.editingReturn.set(r.id);
  }

  protected saveReturn(r: ReturnView): void {
    const charge = Number(this.returnEdit.returnCharge);
    if (this.returnEdit.returnCharge === null || `${this.returnEdit.returnCharge}` === '' || !(charge >= 0)) {
      this.toast.error('Enter a return charge of 0 or more');
      return;
    }
    this.savingReturn.set(true);
    this.api
      .updateReturn(r.id, {
        reason: this.returnEdit.reason,
        paidBy: this.returnEdit.paidBy,
        returnCharge: charge,
        note: this.returnEdit.note.trim() || null,
      })
      .subscribe({
        next: (updated) => {
          this.resource.set(updated);
          this.savingReturn.set(false);
          this.editingReturn.set(null);
          this.toast.success('Return updated');
        },
        error: (err: unknown) => {
          this.savingReturn.set(false);
          this.toast.error(errorMessage(err));
        },
      });
  }

  // Delivered date by hand
  protected readonly editingDelivered = signal(false);
  protected readonly savingDelivered = signal(false);
  protected deliveredInput = '';

  protected startDeliveredEdit(deliveredAt: string): void {
    this.deliveredInput = toBdInputValue(deliveredAt);
    this.editingDelivered.set(true);
  }

  protected saveDelivered(orderId: number): void {
    const at = fromBdInputValue(this.deliveredInput);
    if (!at) {
      this.toast.error('Choose the delivered date');
      return;
    }
    this.savingDelivered.set(true);
    this.api.setDeliveredDate(orderId, at).subscribe({
      next: (updated) => {
        this.resource.set(updated);
        this.savingDelivered.set(false);
        this.editingDelivered.set(false);
        this.toast.success('Delivered date changed');
      },
      error: (err: unknown) => {
        this.savingDelivered.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected courierChargeInput: number | null = null;
  protected returnChargeInput: number | null = null;
  protected readonly savingCourierCost = signal(false);
  /** Fills the courier cost form whenever the order (re)loads. */
  private readonly syncCourierCostForm = effect(() => {
    const cc = this.order()?.courierCost;
    this.courierChargeInput = cc?.courierCharge ?? null;
    this.returnChargeInput = cc?.returnDeliveryCharge ?? null;
  });

  protected saveCourierCost(): void {
    const order = this.order();
    if (!order) {
      return;
    }
    const charge = this.courierChargeInput === null || `${this.courierChargeInput}` === '' ? null : Number(this.courierChargeInput);
    const returned = order.status === 'RETURNED' && !order.courierCost.refused;
    const returnCharge = returned && this.returnChargeInput !== null && `${this.returnChargeInput}` !== '' ? Number(this.returnChargeInput) : null;
    if ((charge !== null && !(charge >= 0)) || (returnCharge !== null && !(returnCharge >= 0))) {
      this.toast.error('Enter an amount of 0 or more');
      return;
    }
    this.savingCourierCost.set(true);
    this.api.recordCourierCost(order.id, charge, returnCharge).subscribe({
      next: (updated) => {
        this.resource.set(updated);
        this.savingCourierCost.set(false);
        this.toast.success('Courier cost saved');
      },
      error: (err: unknown) => {
        this.savingCourierCost.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected submitNote(): void {
    const order = this.order();
    const body = this.noteForm.getRawValue().body.trim();
    if (!order || !body) {
      return;
    }
    this.savingNote.set(true);
    this.api.addNote(order.id, body).subscribe({
      next: (updated) => {
        this.resource.set(updated);
        this.noteForm.reset();
        this.savingNote.set(false);
      },
      error: (err: unknown) => {
        this.toast.error(errorMessage(err));
        this.savingNote.set(false);
      },
    });
  }

  protected onSendClosed(result: SendResult | null): void {
    this.showSendDialog.set(false);
    if (result) {
      this.shipmentResource.reload();
      this.resource.reload();
    }
  }

  protected refreshShipment(): void {
    this.refreshingShipment.set(true);
    this.courierApi.refresh(Number(this.id())).subscribe({
      next: (shipment) => {
        this.shipmentResource.set(shipment);
        this.resource.reload();
        this.refreshingShipment.set(false);
      },
      error: (err: unknown) => {
        this.toast.error(errorMessage(err));
        this.refreshingShipment.set(false);
      },
    });
  }

  protected async remove(): Promise<void> {
    const order = this.order();
    if (!order) {
      return;
    }
    const confirmed = await this.confirm.ask({
      title: `Delete order ${order.orderNumber}?`,
      message: 'The order disappears from every list and report. This cannot be undone from the panel.',
      confirmLabel: 'Delete order',
      danger: true,
    });
    if (!confirmed) {
      return;
    }
    this.api.delete(order.id).subscribe({
      next: () => {
        this.toast.success(`Order ${order.orderNumber} deleted`);
        void this.router.navigate(['/orders']);
      },
      error: (err: unknown) => this.toast.error(errorMessage(err)),
    });
  }

  /** Opens the invoice in a new tab, where the browser's PDF viewer can print it. */
  protected printInvoice(): void {
    const order = this.order();
    if (!order) {
      return;
    }
    // Open the tab inside the click handler so pop-up blockers allow it; fill it when the PDF arrives.
    const tab = window.open('', '_blank');
    this.pdfBusy.set(true);
    this.api.invoicePdf(order.id).subscribe({
      next: (blob) => {
        showBlobInTab(tab, blob, `Invoice_${order.orderNumber}.pdf`);
        this.pdfBusy.set(false);
      },
      error: (err: unknown) => {
        tab?.close();
        this.toast.error(errorMessage(err));
        this.pdfBusy.set(false);
      },
    });
  }

  protected downloadInvoice(): void {
    const order = this.order();
    if (!order) {
      return;
    }
    this.pdfBusy.set(true);
    this.api.invoicePdf(order.id, true).subscribe({
      next: (blob) => {
        saveBlob(blob, `Invoice_${order.orderNumber}.pdf`);
        this.pdfBusy.set(false);
      },
      error: (err: unknown) => {
        this.toast.error(errorMessage(err));
        this.pdfBusy.set(false);
      },
    });
  }

  protected openImages(images: OrderImage[], index = 0): void {
    if (images.length) {
      this.lightbox.set({ images, index });
    }
  }

  protected stepLightbox(delta: number): void {
    const box = this.lightbox();
    if (box) {
      this.lightbox.set({ ...box, index: (box.index + delta + box.images.length) % box.images.length });
    }
  }
}

/** Happy-path steps with completion state; a cancelled/returned order ends in a red final step. */
export function buildTimeline(order: OrderDetail): TimelineStep[] {
  const reachedAt = new Map<OrderStatus, string>();
  order.history.forEach((h) => reachedAt.set(h.toStatus, h.changedAt));

  const failed = order.status === 'CANCELLED' || order.status === 'RETURNED';
  const lastFlowIndex = failed
    ? Math.max(...order.history.map((h) => STATUS_FLOW.indexOf(h.toStatus)))
    : STATUS_FLOW.indexOf(order.status);

  const steps: TimelineStep[] = STATUS_FLOW.map((status, index) => ({
    status,
    label: STATUS_LABELS[status],
    // A step still ahead shows no date, even if the order reached it before being moved back.
    at: index <= lastFlowIndex ? reachedAt.get(status) : undefined,
    state: index < lastFlowIndex || (index === lastFlowIndex && (failed || status === 'DELIVERED'))
      ? 'done'
      : index === lastFlowIndex
        ? 'current'
        : 'upcoming',
  }));

  if (failed) {
    // Drop steps that were never reached and end with the terminal status.
    return [
      ...steps.slice(0, lastFlowIndex + 1),
      { status: order.status, label: STATUS_LABELS[order.status], state: 'failed', at: reachedAt.get(order.status) },
    ];
  }
  return steps;
}
