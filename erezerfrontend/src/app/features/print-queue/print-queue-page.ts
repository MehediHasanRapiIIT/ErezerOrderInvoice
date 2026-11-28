import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { errorMessage } from '../../core/http/api-error';
import { ToastService } from '../../core/notify/toast.service';
import { SearchPage } from '../../shared/search-page';
import { BdDatePipe } from '../../shared/ui/bd-date.pipe';
import { Pagination } from '../../shared/ui/pagination';
import { BulkInvoices, MAX_BULK_INVOICES } from '../orders/data/bulk-invoices';
import { StatusBadge } from '../orders/status-badge';
import { ImageViewer, ViewerImage } from './image-viewer';
import { PrintItem, PrintOrder, PrintQueueApi, PrintQuery } from './print-queue.api';

const PAGE_SIZE = 10;
const DEBOUNCE_MS = 300;

/**
 * Orders waiting to be printed: everything not yet shipped, oldest first, with each product's pictures shown large.
 * An order leaves when it is shipped or cancelled; the "Printed" tick marks the printing as done before that.
 */
@Component({
  selector: 'app-print-queue-page',
  imports: [BdDatePipe, ImageViewer, Pagination, RouterLink, StatusBadge],
  template: `
    <h1 class="page-title">Order to Print</h1>
    <p class="page-subtitle">Orders not yet shipped, with their products to print. An order leaves this list once it is shipped.</p>

    <div class="mt-6 grid grid-cols-3 gap-3">
      <button type="button" class="card p-4 text-left transition hover:border-brand/60" [class.border-brand]="printed() === ''" (click)="setPrinted('')">
        <p class="text-xs text-slate-400">To ship</p><p class="mt-1 text-2xl font-bold text-white">{{ counts()?.total ?? 0 }}</p>
      </button>
      <button type="button" class="card p-4 text-left transition hover:border-brand/60" [class.border-brand]="printed() === 'false'" (click)="setPrinted('false')">
        <p class="text-xs text-slate-400">Not printed</p><p class="mt-1 text-2xl font-bold text-amber-300">{{ counts()?.notPrinted ?? 0 }}</p>
      </button>
      <button type="button" class="card p-4 text-left transition hover:border-brand/60" [class.border-brand]="printed() === 'true'" (click)="setPrinted('true')">
        <p class="text-xs text-slate-400">Printed</p><p class="mt-1 text-2xl font-bold text-emerald-300">{{ counts()?.printed ?? 0 }}</p>
      </button>
    </div>

    <div class="mt-4 flex flex-wrap gap-2">
      <input type="search" class="field w-full sm:w-72" placeholder="Search order number, product code…" [value]="search()"
             (input)="debounced($any($event.target).value)" aria-label="Search" />
      <select class="field w-auto" [value]="printed()" (change)="setPrinted($any($event.target).value)" aria-label="Printed">
        <option value="">All</option>
        <option value="false">Not printed</option>
        <option value="true">Printed</option>
      </select>
      <select class="field w-auto" [value]="sort()" (change)="sort.set($any($event.target).value)" aria-label="Order">
        <option value="oldest">Oldest first</option>
        <option value="newest">Newest first</option>
      </select>
    </div>

    @if (canBulkPrint && (result()?.content?.length ?? 0) > 0) {
      <label class="mt-4 flex w-fit cursor-pointer items-center gap-2 text-sm text-slate-300">
        <input type="checkbox" class="h-4 w-4 accent-blue-600" [checked]="allOnPageSelected()" (change)="selectPage($any($event.target).checked)" />
        Select every order on this page
      </label>
    }

    <div class="mt-4 space-y-4">
      @for (o of result()?.content ?? []; track o.id) {
        <article class="card overflow-hidden" [class.border-red-500/60]="o.urgent">
          <header class="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-line px-4 py-3">
            @if (canBulkPrint) {
              <input type="checkbox" class="h-5 w-5 cursor-pointer accent-blue-600" [checked]="selected().has(o.id)"
                     (change)="toggle(o.id, $any($event.target).checked)" [attr.aria-label]="'Select ' + o.orderNumber" />
            }
            @if (canOpenOrders) {
              <a [routerLink]="['/orders', o.id]" class="font-mono text-lg font-bold text-white hover:underline">{{ o.orderNumber }}</a>
            } @else {
              <span class="font-mono text-lg font-bold text-white">{{ o.orderNumber }}</span>
            }
            <app-status-badge [status]="o.status" />
            @if (o.urgent) {
              <span class="rounded border border-red-500/60 bg-red-500/15 px-2 py-0.5 text-xs font-bold uppercase text-red-300"><i class="fas fa-bolt mr-1"></i>Urgent</span>
            }
            @if (o.orderType === 'EXCHANGE') {
              <span class="rounded border border-brand/50 bg-brand/10 px-2 py-0.5 text-xs font-semibold uppercase text-brand-light"><i class="fas fa-exchange-alt mr-1"></i>Exchange</span>
            }
            @if (o.platform === 'WEBSITE') {
              <span class="rounded border border-sky-500/50 bg-sky-500/10 px-2 py-0.5 text-xs font-semibold uppercase text-sky-300"><i class="fas fa-globe mr-1"></i>Website</span>
            }
            <span class="text-sm text-slate-400">{{ o.orderedAt | bdDate: 'd MMM y, h:mm a' }}</span>
            <div class="ml-auto flex items-center gap-2">
              @if (o.printedAt) {
                <span class="text-xs text-emerald-300"><i class="fas fa-check-circle mr-1"></i>Printed {{ o.printedAt | bdDate: 'd MMM, h:mm a' }}{{ o.printedBy ? ' by ' + o.printedBy : '' }}</span>
              }
              @if (canMark) {
                <button type="button" [class]="o.printedAt ? 'btn-secondary' : 'btn-primary'" [disabled]="saving() === o.id" (click)="togglePrinted(o)">
                  <i class="fas" [class.fa-check]="!o.printedAt" [class.fa-undo]="!!o.printedAt"></i> {{ o.printedAt ? 'Not printed' : 'Mark printed' }}
                </button>
              }
            </div>
          </header>
          <div class="grid gap-4 p-4 sm:grid-cols-2 xl:grid-cols-3">
            @for (item of o.items; track $index) {
              <div class="rounded-lg border border-line p-3">
                @if (item.images.length) {
                  <div class="grid gap-2" [class.grid-cols-2]="item.images.length > 1">
                    @for (img of item.images; track $index; let i = $index) {
                      <button type="button" class="group relative block overflow-hidden rounded-md bg-white/5" (click)="open(o, item, i)"
                              [attr.aria-label]="'Open picture ' + (i + 1) + ' of ' + item.productName">
                        <img [src]="img.url" [alt]="item.productName" loading="lazy"
                             class="w-full object-contain transition group-hover:scale-[1.02]" [class]="item.images.length > 1 ? 'h-40' : 'h-72'" />
                        <span class="absolute right-1 top-1 rounded bg-black/60 px-1.5 py-0.5 text-[10px] text-white opacity-0 group-hover:opacity-100"><i class="fas fa-search-plus"></i></span>
                      </button>
                    }
                  </div>
                } @else {
                  <div class="flex h-40 items-center justify-center rounded-md bg-white/5 text-sm text-slate-500"><i class="fas fa-image mr-2"></i>No picture</div>
                }
                <p class="mt-3 text-base font-semibold text-white">{{ item.productName }}</p>
                <div class="mt-1 flex flex-wrap gap-2 text-sm">
                  @if (item.size) { <span class="rounded bg-white/10 px-2 py-0.5 font-semibold text-white">Size {{ item.size }}</span> }
                  @if (item.color) { <span class="rounded bg-white/5 px-2 py-0.5 text-slate-300">{{ item.color }}</span> }
                  <span class="rounded bg-white/5 px-2 py-0.5 text-slate-300">Qty {{ item.quantity }}</span>
                  @if (item.productCode) { <span class="font-mono text-xs text-slate-500">{{ item.productCode }}</span> }
                </div>
                @for (img of item.images; track $index) {
                  @if (img.description) { <p class="mt-2 whitespace-pre-line text-sm font-semibold text-amber-200">{{ img.description }}</p> }
                }
                @if (item.description) { <p class="mt-1 whitespace-pre-line text-xs text-slate-400">{{ item.description }}</p> }
              </div>
            }
          </div>
        </article>
      } @empty {
        <p class="card p-10 text-center text-slate-500">
          @if (orders.isLoading()) { Loading… } @else { Nothing to print. }
        </p>
      }
      @if (result(); as r) {
        @if (r.totalElements > 0) {
          <app-pagination [page]="r.page" [size]="r.size" [totalElements]="r.totalElements" [totalPages]="r.totalPages" (pageChange)="page.set($event)" />
        }
      }
    </div>

    @if (canBulkPrint && selected().size > 0) {
      <div class="fixed inset-x-0 bottom-0 z-20 border-t border-line bg-panel/95 px-4 py-3 backdrop-blur lg:left-64" role="region" aria-label="Print invoices">
        <div class="flex flex-wrap items-center justify-between gap-3">
          <p class="text-sm text-slate-200"><span class="font-semibold text-white">{{ selected().size }}</span> order{{ selected().size === 1 ? '' : 's' }} selected</p>
          <div class="flex gap-2">
            <button type="button" class="btn-secondary" (click)="selected.set(empty)">Clear</button>
            <button type="button" class="btn-primary" (click)="printSelected()" [disabled]="printingMany() || selected().size > maxInvoices">
              <i class="fas" [class.fa-print]="!printingMany()" [class.fa-circle-notch]="printingMany()" [class.fa-spin]="printingMany()"></i>
              Print invoices{{ selected().size > maxInvoices ? ' (max ' + maxInvoices + ')' : '' }}
            </button>
          </div>
        </div>
      </div>
    }

    @if (viewer(); as v) {
      <app-image-viewer [images]="v.images" [(index)]="v.index" (closed)="viewer.set(null)" />
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PrintQueuePage {
  private readonly api = inject(PrintQueueApi);
  private readonly toast = inject(ToastService);
  private readonly auth = inject(AuthService);

  protected readonly canMark = this.auth.hasPermission('PRINT_QUEUE_MARK');
  protected readonly canOpenOrders = this.auth.hasPermission('ORDER_VIEW');
  protected readonly canBulkPrint = this.auth.hasPermission('INVOICE_BULK_PRINT');
  protected readonly maxInvoices = MAX_BULK_INVOICES;
  protected readonly empty: ReadonlySet<number> = new Set();
  /** Orders ticked for printing their invoices; kept while moving between pages. */
  protected readonly selected = signal<ReadonlySet<number>>(this.empty);
  protected readonly printingMany = signal(false);
  private readonly bulkInvoices = inject(BulkInvoices);

  protected readonly search = signal('');
  protected readonly printed = signal<PrintQuery['printed']>('');
  protected readonly sort = signal<PrintQuery['sort']>('oldest');
  protected readonly page = linkedSignal({ source: () => [this.printed(), this.sort(), this.search()], computation: () => 0 });
  protected readonly saving = signal<number | null>(null);
  protected readonly viewer = signal<{ images: ViewerImage[]; index: number } | null>(null);
  private readonly searchPage = new SearchPage();
  private timer: ReturnType<typeof setTimeout> | undefined;

  protected readonly orders = rxResource({
    params: (): PrintQuery => ({ q: this.search(), printed: this.printed(), sort: this.sort(), page: this.page(), size: PAGE_SIZE }),
    stream: ({ params }) => this.api.list(params),
  });
  private readonly countsData = rxResource({ params: () => this.search(), stream: ({ params }) => this.api.counts(params) });

  protected readonly result = computed(() => (this.orders.hasValue() ? this.orders.value() : undefined));
  protected readonly counts = computed(() => (this.countsData.hasValue() ? this.countsData.value() : undefined));

  constructor() {
    inject(DestroyRef).onDestroy(() => clearTimeout(this.timer));
  }

  protected debounced(value: string): void {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => {
      const q = value.trim();
      const page = this.searchPage.pageFor(this.search(), q, this.page());
      this.search.set(q);
      this.page.set(page);
    }, DEBOUNCE_MS);
  }

  protected readonly allOnPageSelected = computed(() => {
    const rows = this.result()?.content ?? [];
    return rows.length > 0 && rows.every((o) => this.selected().has(o.id));
  });

  protected toggle(id: number, checked: boolean): void {
    const next = new Set(this.selected());
    if (checked) next.add(id);
    else next.delete(id);
    this.selected.set(next);
  }

  protected selectPage(checked: boolean): void {
    const next = new Set(this.selected());
    for (const o of this.result()?.content ?? []) {
      if (checked) next.add(o.id);
      else next.delete(o.id);
    }
    this.selected.set(next);
  }

  /** The ticked orders' invoices in one PDF, in the list's order. */
  protected async printSelected(): Promise<void> {
    const onPage = (this.result()?.content ?? []).map((o) => o.id).filter((id) => this.selected().has(id));
    const others = [...this.selected()].filter((id) => !onPage.includes(id));
    this.printingMany.set(true);
    await this.bulkInvoices.open([...onPage, ...others]);
    this.printingMany.set(false);
  }

  protected setPrinted(value: PrintQuery['printed']): void {
    this.printed.set(value);
  }

  /** Opens the order's pictures, starting at the one clicked. */
  protected open(order: PrintOrder, item: PrintItem, index: number): void {
    const images: ViewerImage[] = [];
    let start = 0;
    for (const it of order.items) {
      it.images.forEach((img, i) => {
        if (it === item && i === index) start = images.length;
        const parts = [it.productName, it.size ? `Size ${it.size}` : '', it.color ?? '', img.description ?? ''].filter(Boolean);
        images.push({ url: img.url, caption: `${order.orderNumber} · ${parts.join(' · ')}` });
      });
    }
    this.viewer.set({ images, index: start });
  }

  protected togglePrinted(order: PrintOrder): void {
    this.saving.set(order.id);
    this.api.markPrinted(order.id, !order.printedAt).subscribe({
      next: () => {
        this.toast.success(order.printedAt ? `${order.orderNumber} marked not printed` : `${order.orderNumber} marked printed`);
        this.saving.set(null);
        this.orders.reload();
        this.countsData.reload();
      },
      error: (err: unknown) => {
        this.toast.error(errorMessage(err));
        this.saving.set(null);
      },
    });
  }
}
