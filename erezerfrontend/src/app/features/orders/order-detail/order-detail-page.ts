import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { errorMessage, toProblem } from '../../../core/http/api-error';
import { saveBlob, showBlobInTab } from '../../../core/http/file-download';
import { ToastService } from '../../../core/notify/toast.service';
import { HasPermissionDirective } from '../../../shared/has-permission.directive';
import { ConfirmService } from '../../../shared/ui/confirm-dialog';
import { TakaPipe } from '../../../shared/ui/taka.pipe';
import { OrderApi } from '../data/order.api';
import { PAYMENT_LABELS, STATUS_FLOW, STATUS_LABELS } from '../data/order-labels';
import { OrderDetail, OrderImage, OrderStatus } from '../data/order.models';
import { StatusBadge } from '../status-badge';

export interface TimelineStep {
  status: OrderStatus;
  label: string;
  state: 'done' | 'current' | 'upcoming' | 'failed';
  at?: string;
}

@Component({
  selector: 'app-order-detail-page',
  imports: [DatePipe, ReactiveFormsModule, RouterLink, HasPermissionDirective, StatusBadge, TakaPipe],
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
  protected readonly savingStatus = signal(false);
  protected readonly savingNote = signal(false);
  protected readonly pdfBusy = signal(false);

  protected readonly statusForm = this.fb.group({
    status: this.fb.control<OrderStatus | ''>('', Validators.required),
    courier: [''],
    trackingNumber: [''],
    returnDeliveryCharge: this.fb.control<number | null>(null, Validators.min(0)),
    note: ['', Validators.maxLength(1000)],
  });

  protected readonly noteForm = this.fb.group({ body: ['', [Validators.required, Validators.maxLength(2000)]] });

  private readonly selectedStatus = signal<OrderStatus | ''>('');

  protected readonly needsShipment = computed(() => this.selectedStatus() === 'SHIPPED');
  protected readonly isReturn = computed(() => this.selectedStatus() === 'RETURNED');

  constructor() {
    // Preselect the next step of the flow whenever the order changes.
    effect(() => {
      const next = this.order()?.allowedNextStatuses[0] ?? '';
      this.statusForm.reset({ status: next, courier: this.order()?.courier ?? '', trackingNumber: this.order()?.trackingNumber ?? '' });
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
    if (value.status === 'CANCELLED' || value.status === 'RETURNED') {
      const confirmed = await this.confirm.ask({
        title: `Mark order as ${STATUS_LABELS[value.status].toLowerCase()}?`,
        message: 'This is final: the order can no longer be edited or moved to another status.',
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
    at: reachedAt.get(status),
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
