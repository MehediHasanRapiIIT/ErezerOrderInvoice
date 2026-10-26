import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { CLOSED_MENU, MenuPosition, placeMenu } from '../../../shared/ui/floating-menu';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Params, Router, RouterLink } from '@angular/router';
import { catchError, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { errorMessage, readBlobError } from '../../../core/http/api-error';
import { fileNameFrom, saveBlob } from '../../../core/http/file-download';
import { ToastService } from '../../../core/notify/toast.service';
import { DATE_RANGES, dateBounds, DateRange, isoDate } from '../../../shared/date-range';
import { HasPermissionDirective } from '../../../shared/has-permission.directive';
import { BdDatePipe, bdToday } from '../../../shared/ui/bd-date.pipe';
import { ConfirmService } from '../../../shared/ui/confirm-dialog';
import { Pagination } from '../../../shared/ui/pagination';
import { TakaPipe } from '../../../shared/ui/taka.pipe';
import { AssetCategoriesDialog } from '../asset-categories/asset-categories-dialog';
import { AssetApi } from '../data/asset.api';
import { Asset, ASSET_STATUS_CLASSES, ASSET_STATUS_LABELS, ASSET_STATUSES, AssetQuery } from '../data/asset.models';

const PAGE_SIZE = 20;
const DEBOUNCE_MS = 350;
const SOON_DAYS = 30;

/** Shop assets. Filter state lives in the URL so reloads and shared links keep it. */
@Component({
  selector: 'app-asset-list-page',
  imports: [BdDatePipe, RouterLink, HasPermissionDirective, Pagination, TakaPipe, AssetCategoriesDialog],
  templateUrl: './asset-list-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AssetListPage {
  private readonly api = inject(AssetApi);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);
  protected readonly auth = inject(AuthService);

  protected readonly dateRanges = [...DATE_RANGES, { value: 'custom' as DateRange, label: 'Custom range' }];
  protected readonly statuses = ASSET_STATUSES;
  protected readonly statusLabels = ASSET_STATUS_LABELS;
  protected readonly statusClasses = ASSET_STATUS_CLASSES;
  protected readonly today = isoDate(bdToday());
  protected readonly soonLimit = isoDate(new Date(bdToday().getTime() + SOON_DAYS * 86_400_000));
  protected readonly canSeeCosts = this.auth.hasPermission('ASSET_COSTS_VIEW');
  protected readonly openMenuId = signal<number | null>(null);
  /** Where the open row menu sits on screen (fixed, so the table's scroll box can't cut it off). */
  protected readonly menuPosition = signal<MenuPosition>(CLOSED_MENU);
  protected readonly exporting = signal(false);
  protected readonly showCategories = signal(false);

  private readonly params = toSignal(this.route.queryParamMap, { initialValue: this.route.snapshot.queryParamMap });

  protected readonly search = computed(() => this.params().get('q') ?? '');
  protected readonly categoryId = computed(() => this.params().get('category') ?? '');
  /** OWNED when absent. */
  protected readonly status = computed(() => this.params().get('status') ?? 'OWNED');
  protected readonly location = computed(() => this.params().get('location') ?? '');
  protected readonly warranty = computed(() => (this.params().get('warranty') as 'SOON' | 'EXPIRED' | null) ?? '');
  protected readonly range = computed<DateRange>(() => (this.params().get('range') as DateRange | null) ?? 'all');
  protected readonly customFrom = computed(() => this.params().get('from') ?? '');
  protected readonly customTo = computed(() => this.params().get('to') ?? '');
  protected readonly page = computed(() => Math.max(0, Number(this.params().get('page') ?? 0) || 0));
  protected readonly hasFilters = computed(
    () =>
      !!this.search() || !!this.categoryId() || this.status() !== 'OWNED' || !!this.location() || !!this.warranty() ||
      this.range() !== 'all',
  );

  private readonly query = computed<AssetQuery>(() => {
    const bounds =
      this.range() === 'custom'
        ? { from: this.customFrom() || undefined, to: this.customTo() || undefined }
        : dateBounds(this.range());
    return {
      q: this.search() || undefined,
      categoryId: this.categoryId() ? Number(this.categoryId()) : undefined,
      status: this.status(),
      location: this.location() || undefined,
      warranty: this.warranty() || undefined,
      ...bounds,
      page: this.page(),
      size: PAGE_SIZE,
    };
  });

  protected readonly assets = rxResource({ params: () => this.query(), stream: ({ params }) => this.api.list(params) });
  protected readonly categories = rxResource({ stream: () => this.api.categories() });
  protected readonly locations = toSignal(this.api.locations().pipe(catchError(() => of([] as string[]))), {
    initialValue: [] as string[],
  });

  protected readonly result = computed(() => (this.assets.hasValue() ? this.assets.value() : undefined));
  protected readonly rows = computed(() => this.result()?.content ?? []);
  protected readonly summary = computed(() => this.result()?.summary);
  protected readonly categoryOptions = computed(() => (this.categories.hasValue() ? this.categories.value() : []));

  private timer: ReturnType<typeof setTimeout> | undefined;

  constructor() {
    inject(DestroyRef).onDestroy(() => clearTimeout(this.timer));
  }

  protected debounced(value: string): void {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.updateParams({ q: value.trim() || null, page: null }), DEBOUNCE_MS);
  }

  protected setFilter(key: 'category' | 'status' | 'location' | 'warranty' | 'range' | 'from' | 'to', value: string): void {
    const empty = !value || (key === 'range' && value === 'all') || (key === 'status' && value === 'OWNED');
    const changes: Params = { [key]: empty ? null : value, page: null };
    if (key === 'range' && value !== 'custom') {
      changes['from'] = null;
      changes['to'] = null;
    }
    this.updateParams(changes);
  }

  /** Summary tiles act as quick filters. */
  protected quick(kind: 'repair' | 'warranty'): void {
    this.updateParams(
      kind === 'repair'
        ? { status: 'UNDER_REPAIR', warranty: null, page: null }
        : { status: null, warranty: 'SOON', page: null },
    );
  }

  protected clearFilters(): void {
    this.updateParams({ q: null, category: null, status: null, location: null, warranty: null, range: null, from: null, to: null, page: null });
  }

  protected goToPage(page: number): void {
    this.updateParams({ page: page > 0 ? page : null });
  }

  protected toggleMenu(id: number, event: Event): void {
    event.stopPropagation();
    if (this.openMenuId() === id) {
      this.openMenuId.set(null);
      return;
    }
    this.menuPosition.set(placeMenu(event, () => this.openMenuId.set(null), 110));
    this.openMenuId.set(id);
  }

  protected open(asset: Asset): void {
    void this.router.navigate(['/assets', asset.id]);
  }

  /** 'expired' | 'soon' | '' for the warranty column. */
  protected warrantyState(asset: Asset): string {
    if (!asset.warrantyUntil) return '';
    if (asset.warrantyUntil < this.today) return 'expired';
    return asset.warrantyUntil <= this.soonLimit ? 'soon' : '';
  }

  protected exportExcel(): void {
    this.exporting.set(true);
    this.api.exportExcel(this.query()).subscribe({
      next: (response) => {
        saveBlob(response.body!, fileNameFrom(response, 'assets.xlsx'));
        this.exporting.set(false);
      },
      error: (err: unknown) => {
        void readBlobError(err).then((message) => this.toast.error(message));
        this.exporting.set(false);
      },
    });
  }

  protected async remove(asset: Asset): Promise<void> {
    this.openMenuId.set(null);
    const confirmed = await this.confirm.ask({
      title: 'Delete this asset?',
      message: `“${asset.name}” will be removed from the list and the totals. Use Sold or Disposed instead if it simply left the shop.`,
      confirmLabel: 'Delete asset',
      danger: true,
    });
    if (!confirmed) {
      return;
    }
    this.api.delete(asset.id).subscribe({
      next: () => {
        this.toast.success('Asset deleted');
        this.assets.reload();
      },
      error: (err: unknown) => this.toast.error(errorMessage(err)),
    });
  }

  protected onCategoriesClosed(changed: boolean): void {
    this.showCategories.set(false);
    if (changed) {
      this.categories.reload();
      this.assets.reload();
    }
  }

  private updateParams(changes: Params): void {
    void this.router.navigate([], { relativeTo: this.route, queryParams: changes, queryParamsHandling: 'merge' });
  }
}
