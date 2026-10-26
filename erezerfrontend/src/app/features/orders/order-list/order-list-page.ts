import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { CLOSED_MENU, MenuPosition, placeMenu } from '../../../shared/ui/floating-menu';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Params, Router, RouterLink } from '@angular/router';
import { map } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { errorMessage, readBlobError } from '../../../core/http/api-error';
import { fileNameFrom, saveBlob } from '../../../core/http/file-download';
import { ToastService } from '../../../core/notify/toast.service';
import { HasPermissionDirective } from '../../../shared/has-permission.directive';
import { ConfirmService } from '../../../shared/ui/confirm-dialog';
import { Pagination } from '../../../shared/ui/pagination';
import { DATE_RANGES, dateBounds, DateRange } from '../../../shared/date-range';
import { TakaPipe } from '../../../shared/ui/taka.pipe';
import { OrderApi } from '../data/order.api';
import { isEditableStatus, PAYMENT_LABELS, PAYMENT_METHODS, STATUS_LABELS, TAB_STATUSES } from '../data/order-labels';
import { OrderQuery, OrderStatus, OrderSummary, OrderTab, OrderType, PaymentMethod } from '../data/order.models';
import { StatusBadge } from '../status-badge';
import { CourierApi, SENDABLE_STATUSES, SendResult } from '../../courier/courier.api';
import { SendToCourierDialog } from '../../courier/send-to-courier-dialog';
import { showBlobInTab } from '../../../core/http/file-download';
import { BdDatePipe } from '../../../shared/ui/bd-date.pipe';
import { FormsModule } from '@angular/forms';
import { NgTemplateOutlet } from '@angular/common';
import { DateTimePicker } from '../../../shared/ui/date-time-picker';
import { toBdInputValue } from '../../../shared/bd-datetime-input';

const PAGE_SIZE = 20;
const SEARCH_DEBOUNCE_MS = 350;

/** Order list. All filter state lives in the URL, so reload, back/forward and shared links keep it. */
const BALANCE_FILTERS: readonly string[] = ['ADVANCE', 'DUE'];

@Component({
  selector: 'app-order-list-page',
  imports: [BdDatePipe, FormsModule, NgTemplateOutlet, RouterLink, HasPermissionDirective, Pagination, StatusBadge, TakaPipe, SendToCourierDialog, DateTimePicker],
  host: { class: 'block pb-20' },
  templateUrl: './order-list-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderListPage {
  private readonly api = inject(OrderApi);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);
  protected readonly auth = inject(AuthService);

  protected readonly dateRanges = DATE_RANGES;
  protected readonly paymentMethods = PAYMENT_METHODS;
  protected readonly paymentLabels = PAYMENT_LABELS;
  protected readonly statusLabels = STATUS_LABELS;
  protected readonly openMenuId = signal<number | null>(null);
  /** Where the open row menu sits on screen (fixed, so the table's scroll box can't cut it off). */
  protected readonly menuPosition = signal<MenuPosition>(CLOSED_MENU);
  protected readonly exporting = signal(false);

  private readonly params = toSignal(this.route.queryParamMap.pipe(map((p) => p)), {
    initialValue: this.route.snapshot.queryParamMap,
  });

  protected readonly tab = computed<OrderTab>(() => (this.params().get('tab') === 'history' ? 'history' : 'active'));
  protected readonly search = computed(() => this.params().get('q') ?? '');
  protected readonly status = computed(() => (this.params().get('status') as OrderStatus | null) ?? '');
  protected readonly payment = computed(() => this.params().get('payment') ?? '');
  protected readonly type = computed(() => (this.params().get('type') as OrderType | null) ?? '');
  protected readonly range = computed<DateRange>(() => (this.params().get('range') as DateRange | null) ?? 'all');
  protected readonly page = computed(() => Math.max(0, Number(this.params().get('page') ?? 0) || 0));
  protected readonly tabStatuses = computed(() => TAB_STATUSES[this.tab()]);
  protected readonly hasFilters = computed(
    () => !!this.search() || !!this.status() || !!this.payment() || !!this.type() || this.range() !== 'all',
  );

  private readonly query = computed<OrderQuery>(() => ({
    tab: this.tab(),
    q: this.search() || undefined,
    status: this.status() || undefined,
    // "Advance paid" and "Due" share the payment dropdown but filter by balance.
    paymentMethod: this.payment() && !BALANCE_FILTERS.includes(this.payment()) ? (this.payment() as PaymentMethod) : undefined,
    balance: BALANCE_FILTERS.includes(this.payment()) ? (this.payment() as 'ADVANCE' | 'DUE') : undefined,
    type: this.type() || undefined,
    ...dateBounds(this.range()),
    page: this.page(),
    size: PAGE_SIZE,
  }));

  protected readonly orders = rxResource({
    params: () => this.query(),
    stream: ({ params }) => this.api.list(params),
  });

  protected readonly stats = rxResource({ stream: () => this.api.stats() });

  protected readonly statCards = computed(() => {
    const stats = this.stats.hasValue() ? this.stats.value() : null;
    return [
      { label: 'Total Orders', value: stats?.total, icon: 'fa-shopping-bag', tone: 'text-white', chip: 'bg-blue-500/15 text-blue-300' },
      { label: 'Pending', value: stats?.pending, icon: 'fa-clock', tone: 'text-amber-300', chip: 'bg-amber-500/15 text-amber-300' },
      { label: 'Out for Delivery', value: stats?.inTransit, icon: 'fa-truck', tone: 'text-fuchsia-300', chip: 'bg-fuchsia-500/15 text-fuchsia-300' },
      { label: 'Completed', value: stats?.delivered, icon: 'fa-check-circle', tone: 'text-emerald-300', chip: 'bg-emerald-500/15 text-emerald-300' },
    ];
  });

  /** Rows to render; empty while loading or on error (value() throws in the error state). */
  protected readonly rows = computed(() => (this.orders.hasValue() ? this.orders.value().content : []));

  // ---- Selection for bulk courier sending ----
  private readonly courierApi = inject(CourierApi);
  private readonly courierAvailability = rxResource({ stream: () => this.courierApi.availability() });
  protected readonly canBulkSend = computed(() => this.auth.hasPermission('COURIER_SEND')
    && this.courierAvailability.hasValue() && this.courierAvailability.value().enabled);

  // ---- Row actions: send one order to Steadfast, print its invoice ----
  /** The order whose Send to Steadfast preview is open. */
  protected readonly sendOrderId = signal<number | null>(null);
  protected readonly printingId = signal<number | null>(null);

  protected isSendable(order: OrderSummary): boolean {
    return SENDABLE_STATUSES.includes(order.status);
  }

  protected onSendClosed(result: SendResult | null): void {
    this.sendOrderId.set(null);
    if (result) {
      this.refresh();
    }
  }

  protected printInvoice(order: OrderSummary): void {
    // Open the tab inside the click so pop-up blockers allow it; fill it when the PDF arrives.
    const tab = window.open('', '_blank');
    this.printingId.set(order.id);
    this.api.invoicePdf(order.id).subscribe({
      next: (blob) => {
        showBlobInTab(tab, blob, `Invoice_${order.orderNumber}.pdf`);
        this.printingId.set(null);
      },
      error: (err: unknown) => {
        tab?.close();
        this.toast.error(errorMessage(err));
        this.printingId.set(null);
      },
    });
  }
  // ---- Dates: change one from the list, or many at once ----
  protected readonly canEditDates = this.auth.hasPermission('ORDER_DATE_EDIT');
  /** Ticking orders is offered for sending to the courier and for changing dates. */
  protected readonly canSelect = computed(() => this.canBulkSend() || this.canEditDates);
  protected readonly editingDateId = signal<number | null>(null);
  protected inlineDate = '';
  protected readonly savingDate = signal(false);
  protected readonly bulkDateOpen = signal(false);
  protected bulkField: 'ORDER' | 'DELIVERED' = 'ORDER';
  protected bulkDate = '';
  protected bulkKeepTime = true;

  protected startDateEdit(order: OrderSummary, event: Event): void {
    event.stopPropagation();
    this.inlineDate = toBdInputValue(order.createdAt);
    this.editingDateId.set(order.id);
  }

  protected saveInlineDate(order: OrderSummary): void {
    this.applyDate([order.id], 'ORDER', this.inlineDate, false, () => this.editingDateId.set(null));
  }

  protected openBulkDate(): void {
    this.bulkField = 'ORDER';
    this.bulkDate = toBdInputValue();
    this.bulkKeepTime = true;
    this.bulkDateOpen.set(true);
  }

  protected saveBulkDate(): void {
    this.applyDate([...this.selected()], this.bulkField, this.bulkDate, this.bulkKeepTime, () => {
      this.bulkDateOpen.set(false);
      this.clearSelection();
    });
  }

  /** @param value "YYYY-MM-DDTHH:mm" (Bangladesh time) */
  private applyDate(ids: number[], field: 'ORDER' | 'DELIVERED', value: string, keepTime: boolean, done: () => void): void {
    if (!value) {
      this.toast.error('Choose the date');
      return;
    }
    this.savingDate.set(true);
    this.api.bulkDate(ids, field, value.slice(0, 10), keepTime ? null : value.slice(11, 16)).subscribe({
      next: ({ changed, skipped }) => {
        this.savingDate.set(false);
        done();
        const what = field === 'ORDER' ? 'order date' : 'delivered date';
        if (changed) this.toast.success(`${changed} ${what}${changed === 1 ? '' : 's'} changed`);
        if (skipped.length) {
          this.toast.error(`${skipped.length} left as they were: ${skipped.slice(0, 3).join('; ')}${skipped.length > 3 ? '…' : ''}`);
        } else if (!changed) {
          this.toast.info('Nothing to change: the date is the same');
        }
        this.refresh();
      },
      error: (err: unknown) => {
        this.savingDate.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected readonly selected = signal<ReadonlySet<number>>(new Set());
  protected readonly selectingAll = signal(false);
  protected readonly allPageSelected = computed(() =>
    this.rows().length > 0 && this.rows().every((o) => this.selected().has(o.id)));
  protected readonly somePageSelected = computed(() =>
    !this.allPageSelected() && this.rows().some((o) => this.selected().has(o.id)));
  protected readonly matchingTotal = computed(() => (this.orders.hasValue() ? this.orders.value().totalElements : 0));

  protected isSelected(id: number): boolean {
    return this.selected().has(id);
  }

  protected toggleRow(id: number, event: Event): void {
    event.stopPropagation();
    const next = new Set(this.selected());
    if (next.has(id)) {
      next.delete(id);
    } else {
      next.add(id);
    }
    this.selected.set(next);
  }

  protected togglePage(): void {
    const next = new Set(this.selected());
    const select = !this.allPageSelected();
    this.rows().forEach((o) => (select ? next.add(o.id) : next.delete(o.id)));
    this.selected.set(next);
  }

  /** Selects every order matching the current filters (the server caps this at 500). */
  protected selectAllMatching(): void {
    this.selectingAll.set(true);
    this.api.ids(this.query()).subscribe({
      next: ({ ids, total }) => {
        this.selected.set(new Set(ids));
        this.selectingAll.set(false);
        if (total > ids.length) {
          this.toast.info(`Selected the first ${ids.length} of ${total} orders (the courier accepts 500 per batch)`);
        }
      },
      error: (err: unknown) => {
        this.selectingAll.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected clearSelection(): void {
    this.selected.set(new Set());
  }

  protected sendSelectedToCourier(): void {
    void this.router.navigate(['/orders/send-to-courier'], { queryParams: { ids: [...this.selected()].join(',') } });
  }

  private searchTimer: ReturnType<typeof setTimeout> | undefined;

  constructor() {
    inject(DestroyRef).onDestroy(() => clearTimeout(this.searchTimer));
  }

  protected setTab(tab: OrderTab): void {
    // Status options differ per tab, so the status filter resets.
    this.updateParams({ tab: tab === 'active' ? null : tab, status: null, page: null });
  }

  protected onSearch(value: string): void {
    clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => this.updateParams({ q: value.trim() || null, page: null }), SEARCH_DEBOUNCE_MS);
  }

  protected setFilter(key: 'status' | 'payment' | 'type' | 'range', value: string): void {
    this.updateParams({ [key]: value && value !== 'all' ? value : null, page: null });
  }

  protected clearFilters(): void {
    this.updateParams({ q: null, status: null, payment: null, type: null, range: null, page: null });
  }

  protected goToPage(page: number): void {
    this.updateParams({ page: page > 0 ? page : null });
  }

  /** Downloads every order matching the current filters (not just this page). */
  protected exportExcel(): void {
    this.exporting.set(true);
    this.api.exportExcel(this.query()).subscribe({
      next: (response) => {
        saveBlob(response.body!, fileNameFrom(response, 'orders.xlsx'));
        this.exporting.set(false);
      },
      error: (err: unknown) => {
        // Errors arrive as a Blob because of responseType: 'blob'.
        void readBlobError(err).then((message) => this.toast.error(message));
        this.exporting.set(false);
      },
    });
  }

  protected refresh(): void {
    this.orders.reload();
    this.stats.reload();
  }

  protected open(order: OrderSummary): void {
    void this.router.navigate(['/orders', order.id]);
  }

  protected toggleMenu(id: number, event: Event): void {
    event.stopPropagation();
    if (this.openMenuId() === id) {
      this.openMenuId.set(null);
      return;
    }
    this.menuPosition.set(placeMenu(event, () => this.openMenuId.set(null), 170));
    this.openMenuId.set(id);
  }

  protected canEdit(order: OrderSummary): boolean {
    return this.auth.hasPermission('ORDER_EDIT') && isEditableStatus(order.status);
  }

  protected async remove(order: OrderSummary): Promise<void> {
    this.openMenuId.set(null);
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
        this.refresh();
      },
      error: (err: unknown) => this.toast.error(errorMessage(err)),
    });
  }

  private updateParams(changes: Params): void {
    void this.router.navigate([], { relativeTo: this.route, queryParams: changes, queryParamsHandling: 'merge' });
  }
}
