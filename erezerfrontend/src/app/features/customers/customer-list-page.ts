import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Params, Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { readBlobError } from '../../core/http/api-error';
import { fileNameFrom, saveBlob } from '../../core/http/file-download';
import { ToastService } from '../../core/notify/toast.service';
import { HasPermissionDirective } from '../../shared/has-permission.directive';
import { Pagination } from '../../shared/ui/pagination';
import { TakaPipe } from '../../shared/ui/taka.pipe';
import { Customer, CustomerApi, CustomerQuery, CustomerSegment, CustomerSort } from './customer.api';
import { BdDatePipe } from '../../shared/ui/bd-date.pipe';

const PAGE_SIZE = 20;

const SORTS: { value: CustomerSort; label: string }[] = [
  { value: 'lastOrder', label: 'Most recent order' },
  { value: 'totalPaid', label: 'Highest total paid' },
  { value: 'orders', label: 'Most orders' },
  { value: 'name', label: 'Name (A–Z)' },
];

/** Customers aggregated from orders (one per phone number). Filter state lives in the URL. */
@Component({
  selector: 'app-customer-list-page',
  imports: [BdDatePipe, HasPermissionDirective, Pagination, TakaPipe],
  templateUrl: './customer-list-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CustomerListPage {
  private readonly api = inject(CustomerApi);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly toast = inject(ToastService);
  protected readonly auth = inject(AuthService);

  protected readonly sorts = SORTS;
  protected readonly exporting = signal(false);

  private readonly params = toSignal(this.route.queryParamMap, { initialValue: this.route.snapshot.queryParamMap });
  protected readonly search = computed(() => this.params().get('q') ?? '');
  protected readonly segment = computed(() => (this.params().get('segment') as CustomerSegment | null) ?? '');
  protected readonly sort = computed(() => (this.params().get('sort') as CustomerSort | null) ?? 'lastOrder');
  protected readonly page = computed(() => Math.max(0, Number(this.params().get('page') ?? 0) || 0));

  private readonly query = computed<CustomerQuery>(() => ({
    q: this.search() || undefined,
    segment: this.segment() || undefined,
    sort: this.sort(),
    page: this.page(),
    size: PAGE_SIZE,
  }));

  protected readonly customers = rxResource({ params: () => this.query(), stream: ({ params }) => this.api.list(params) });
  protected readonly result = computed(() => (this.customers.hasValue() ? this.customers.value() : undefined));
  protected readonly rows = computed(() => this.result()?.content ?? []);

  private timer: ReturnType<typeof setTimeout> | undefined;

  constructor() {
    inject(DestroyRef).onDestroy(() => clearTimeout(this.timer));
  }

  protected onSearch(value: string): void {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.updateParams({ q: value.trim() || null, page: null }), 350);
  }

  protected setSegment(segment: CustomerSegment | ''): void {
    this.updateParams({ segment: segment || null, page: null });
  }

  protected setSort(sort: string): void {
    this.updateParams({ sort: sort === 'lastOrder' ? null : sort, page: null });
  }

  protected goToPage(page: number): void {
    this.updateParams({ page: page > 0 ? page : null });
  }

  /** Share of finished orders that came back, to flag customers who refuse COD parcels. */
  protected returnRate(c: Customer): number | null {
    const finished = c.delivered + c.returned;
    return finished === 0 ? null : c.returned / finished;
  }

  protected openOrders(c: Customer): void {
    if (this.auth.hasPermission('ORDER_VIEW')) {
      void this.router.navigate(['/orders'], { queryParams: { q: c.phoneKey } });
    }
  }

  protected exportExcel(): void {
    this.exporting.set(true);
    this.api.exportExcel(this.query()).subscribe({
      next: (response) => {
        saveBlob(response.body!, fileNameFrom(response, 'customers.xlsx'));
        this.exporting.set(false);
      },
      error: (err: unknown) => {
        void readBlobError(err).then((message) => this.toast.error(message));
        this.exporting.set(false);
      },
    });
  }

  private updateParams(changes: Params): void {
    void this.router.navigate([], { relativeTo: this.route, queryParams: changes, queryParamsHandling: 'merge' });
  }
}
