import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { readBlobError } from '../../core/http/api-error';
import { fileNameFrom, saveBlob } from '../../core/http/file-download';
import { ToastService } from '../../core/notify/toast.service';
import { isoDate } from '../../shared/date-range';
import { BdDatePipe, bdToday } from '../../shared/ui/bd-date.pipe';
import { Pagination } from '../../shared/ui/pagination';
import { TakaPipe } from '../../shared/ui/taka.pipe';
import { SoldProductsApi, SoldQuery } from './sold-products.api';

const PAGE_SIZE = 20;

type Preset = 'today' | '7d' | '30d' | 'month' | 'lastMonth' | 'year' | 'all' | 'custom';

const PRESETS: { value: Preset; label: string }[] = [
  { value: 'today', label: 'Today' },
  { value: '7d', label: 'Last 7 days' },
  { value: '30d', label: 'Last 30 days' },
  { value: 'month', label: 'This month' },
  { value: 'lastMonth', label: 'Last month' },
  { value: 'year', label: 'This year' },
  { value: 'all', label: 'All time' },
  { value: 'custom', label: 'Custom dates' },
];

/**
 * Sold products by category (the product name on the order): delivered sales only; each order's discount, delivery
 * charge and courier cost are shared between its categories by pieces.
 */
@Component({
  selector: 'app-sold-products-page',
  imports: [RouterLink, BdDatePipe, TakaPipe, Pagination],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './sold-products-page.html',
})
export class SoldProductsPage {
  private readonly api = inject(SoldProductsApi);
  private readonly toast = inject(ToastService);

  protected readonly presets = PRESETS;
  protected readonly preset = signal<Preset>('month');
  protected readonly customFrom = signal(isoDate(new Date(bdToday().getFullYear(), bdToday().getMonth(), 1)));
  protected readonly customTo = signal(isoDate(bdToday()));
  protected readonly category = signal('');
  protected readonly search = signal('');
  protected readonly page = signal(0);
  protected readonly exporting = signal(false);
  private searchTimer?: ReturnType<typeof setTimeout>;

  protected readonly range = computed(() => {
    const today = bdToday();
    const y = today.getFullYear();
    const m = today.getMonth();
    const d = today.getDate();
    const day = (yy: number, mm: number, dd: number) => isoDate(new Date(yy, mm, dd));
    switch (this.preset()) {
      case 'today': return { from: isoDate(today), to: isoDate(today) };
      case '7d': return { from: day(y, m, d - 6), to: isoDate(today) };
      case '30d': return { from: day(y, m, d - 29), to: isoDate(today) };
      case 'lastMonth': return { from: day(y, m - 1, 1), to: day(y, m, 0) };
      case 'year': return { from: day(y, 0, 1), to: isoDate(today) };
      case 'all': return { from: '2020-01-01', to: isoDate(today) };
      case 'custom': return { from: this.customFrom(), to: this.customTo() };
      default: return { from: day(y, m, 1), to: isoDate(today) };
    }
  });

  private readonly query = computed<SoldQuery>(() => ({ ...this.range(), category: this.category() || undefined, q: this.search() || undefined }));

  protected readonly summary = rxResource({ params: () => this.query(), stream: ({ params }) => this.api.summary(params) });
  protected readonly rows = rxResource({
    params: () => ({ query: this.query(), page: this.page() }),
    stream: ({ params }) => this.api.rows(params.query, params.page, PAGE_SIZE),
  });

  protected readonly categories = computed(() => (this.summary.hasValue() ? this.summary.value().categories : []));
  protected readonly totals = computed(() => (this.summary.hasValue() ? this.summary.value().totals : null));
  protected readonly selectedName = computed(() => this.categories().find((c) => c.categoryKey === this.category())?.category ?? this.category());

  protected setPreset(value: Preset): void {
    this.preset.set(value);
    this.page.set(0);
  }

  protected setCustom(which: 'from' | 'to', value: string): void {
    if (!value) return;
    (which === 'from' ? this.customFrom : this.customTo).set(value);
    this.page.set(0);
  }

  protected setCategory(key: string): void {
    this.category.set(key);
    this.page.set(0);
  }

  protected onSearch(value: string): void {
    clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => {
      this.search.set(value.trim());
      this.page.set(0);
    }, 300);
  }

  protected exportExcel(): void {
    this.exporting.set(true);
    this.api.exportExcel(this.query()).subscribe({
      next: (response) => {
        saveBlob(response.body!, fileNameFrom(response, 'sold-products.xlsx'));
        this.exporting.set(false);
      },
      error: (err: unknown) => {
        void readBlobError(err).then((message) => this.toast.error(message));
        this.exporting.set(false);
      },
    });
  }

  /** Share of the category in the total sale, for the bar in the category table. */
  protected share(sale: number): number {
    const all = this.categories().reduce((sum, c) => sum + c.sale, 0);
    return all > 0 ? Math.max(0, (sale / all) * 100) : 0;
  }
}
