import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { errorMessage } from '../../core/http/api-error';
import { ToastService } from '../../core/notify/toast.service';
import { HasPermissionDirective } from '../../shared/has-permission.directive';
import { BdDatePipe } from '../../shared/ui/bd-date.pipe';
import { ConfirmService } from '../../shared/ui/confirm-dialog';
import { Pagination } from '../../shared/ui/pagination';
import { StockApi, StockMode, StockMovement, Variant } from './stock.api';

const PAGE_SIZE = 20;

type Dialog = 'add' | 'bulk-add' | 'edit' | 'bulk-update' | 'history' | null;

interface NewRow {
  name: string;
  quantity: number | null;
}

interface UpdateRow {
  variant: Variant;
  mode: StockMode;
  amount: number | null;
}

/** Stock: variants with how many are in stock; add and update one at a time or in bulk; see every change. */
@Component({
  selector: 'app-stock-page',
  imports: [FormsModule, RouterLink, BdDatePipe, HasPermissionDirective, Pagination],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './stock-page.html',
})
export class StockPage {
  private readonly api = inject(StockApi);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);
  protected readonly auth = inject(AuthService);
  protected readonly Math = Math;

  protected readonly search = signal('');
  protected readonly lowOnly = signal(false);
  protected readonly page = signal(0);
  protected readonly sort = signal('name,asc');
  private searchTimer?: ReturnType<typeof setTimeout>;

  protected readonly list = rxResource({
    params: () => ({ q: this.search(), low: this.lowOnly(), page: this.page(), size: PAGE_SIZE, sort: this.sort() }),
    stream: ({ params }) => this.api.list(params),
  });
  protected readonly summary = rxResource({ stream: () => this.api.summary() });
  protected readonly rows = computed(() => (this.list.hasValue() ? this.list.value().content : []));

  // Selection for bulk update (kept across pages).
  protected readonly selected = signal<Map<number, Variant>>(new Map());
  protected readonly allOnPageSelected = computed(() => this.rows().length > 0 && this.rows().every((r) => this.selected().has(r.id)));

  protected readonly dialog = signal<Dialog>(null);
  protected readonly saving = signal(false);

  // Add one
  protected addName = '';
  protected addQuantity: number | null = 0;
  // Add many
  protected readonly newRows = signal<NewRow[]>([]);
  // Edit one
  protected readonly editing = signal<Variant | null>(null);
  protected editName = '';
  protected editQuantity: number | null = 0;
  protected editNote = '';
  // Update many
  protected readonly updateRows = signal<UpdateRow[]>([]);
  protected updateNote = '';
  // History
  protected readonly historyOf = signal<Variant | null>(null);
  protected readonly history = signal<StockMovement[]>([]);
  protected readonly historyPage = signal(0);
  protected readonly historyMore = signal(false);

  protected onSearch(value: string): void {
    clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => {
      this.search.set(value.trim());
      this.page.set(0);
    }, 250);
  }

  protected setLow(value: boolean): void {
    this.lowOnly.set(value);
    this.page.set(0);
  }

  protected sortBy(field: 'name' | 'quantity' | 'updatedAt'): void {
    const [current, dir] = this.sort().split(',');
    this.sort.set(`${field},${current === field && dir === 'asc' ? 'desc' : 'asc'}`);
  }

  protected sortIcon(field: string): string {
    const [current, dir] = this.sort().split(',');
    return current !== field ? 'fa-sort text-slate-600' : dir === 'asc' ? 'fa-sort-up' : 'fa-sort-down';
  }

  protected toggle(v: Variant, checked: boolean): void {
    this.selected.update((m) => {
      const next = new Map(m);
      if (checked) next.set(v.id, v);
      else next.delete(v.id);
      return next;
    });
  }

  protected toggleAll(checked: boolean): void {
    this.selected.update((m) => {
      const next = new Map(m);
      for (const v of this.rows()) {
        if (checked) next.set(v.id, v);
        else next.delete(v.id);
      }
      return next;
    });
  }

  protected clearSelection(): void {
    this.selected.set(new Map());
  }

  // ---- Add ----

  protected openAdd(): void {
    this.addName = '';
    this.addQuantity = 0;
    this.dialog.set('add');
  }

  protected saveAdd(): void {
    const name = this.addName.trim();
    const quantity = Number(this.addQuantity);
    if (!name || !Number.isInteger(quantity) || quantity < 0) {
      this.toast.error('Enter a name and a quantity of 0 or more');
      return;
    }
    this.run(this.api.create(name, quantity), `${name} added`);
  }

  protected openBulkAdd(): void {
    this.newRows.set(Array.from({ length: 5 }, () => ({ name: '', quantity: null })));
    this.dialog.set('bulk-add');
  }

  protected addRow(): void {
    this.newRows.update((rows) => [...rows, { name: '', quantity: null }]);
  }

  protected removeRow(index: number): void {
    this.newRows.update((rows) => rows.filter((_, i) => i !== index));
  }

  protected setNewRow(index: number, patch: Partial<NewRow>): void {
    this.newRows.update((rows) => rows.map((r, i) => (i === index ? { ...r, ...patch } : r)));
  }

  protected readonly filledRows = computed(() => this.newRows().filter((r) => r.name.trim() !== ''));

  protected saveBulkAdd(): void {
    const rows = this.filledRows();
    if (!rows.length) {
      this.toast.error('Enter at least one variant name');
      return;
    }
    if (rows.some((r) => !Number.isInteger(Number(r.quantity ?? 0)) || Number(r.quantity ?? 0) < 0)) {
      this.toast.error('Quantities must be whole numbers, 0 or more');
      return;
    }
    this.run(this.api.createAll(rows.map((r) => ({ name: r.name.trim(), quantity: Number(r.quantity ?? 0) }))),
      `${rows.length} variant${rows.length === 1 ? '' : 's'} added`);
  }

  // ---- Update ----

  protected openEdit(v: Variant): void {
    this.editing.set(v);
    this.editName = v.name;
    this.editQuantity = v.quantity;
    this.editNote = '';
    this.dialog.set('edit');
  }

  protected editChange(): number {
    const v = this.editing();
    return v ? (Number(this.editQuantity) || 0) - v.quantity : 0;
  }

  protected saveEdit(): void {
    const v = this.editing();
    const quantity = Number(this.editQuantity);
    if (!v || !this.editName.trim() || !Number.isInteger(quantity) || quantity < 0) {
      this.toast.error('Enter a name and a quantity of 0 or more');
      return;
    }
    this.run(this.api.update(v.id, { name: this.editName.trim(), quantity, note: this.editNote.trim() || null, version: v.version }),
      `${this.editName.trim()} updated`);
  }

  protected openBulkUpdate(): void {
    this.updateRows.set([...this.selected().values()].map((variant) => ({ variant, mode: 'ADD', amount: null })));
    this.updateNote = '';
    this.dialog.set('bulk-update');
  }

  protected setUpdateRow(index: number, patch: Partial<UpdateRow>): void {
    this.updateRows.update((rows) => rows.map((r, i) => (i === index ? { ...r, ...patch } : r)));
  }

  protected setAllModes(mode: StockMode): void {
    this.updateRows.update((rows) => rows.map((r) => ({ ...r, mode })));
  }

  protected newQuantity(r: UpdateRow): number {
    const amount = Number(r.amount) || 0;
    return r.mode === 'SET' ? amount : r.mode === 'ADD' ? r.variant.quantity + amount : r.variant.quantity - amount;
  }

  protected saveBulkUpdate(): void {
    const rows = this.updateRows().filter((r) => r.amount !== null && `${r.amount}` !== '');
    if (!rows.length) {
      this.toast.error('Enter an amount for at least one variant');
      return;
    }
    if (rows.some((r) => !Number.isInteger(Number(r.amount)) || Number(r.amount) < 0 || this.newQuantity(r) < 0)) {
      this.toast.error('Amounts must be whole numbers, and stock cannot go below 0');
      return;
    }
    this.run(this.api.updateAll(rows.map((r) => ({ id: r.variant.id, mode: r.mode, amount: Number(r.amount) })), this.updateNote.trim() || null),
      `${rows.length} variant${rows.length === 1 ? '' : 's'} updated`, true);
  }

  // ---- Delete / history ----

  protected async remove(v: Variant): Promise<void> {
    const ok = await this.confirm.ask({
      title: `Delete ${v.name}?`,
      message: 'It disappears from the list and from the order form. Orders that used it keep its name.',
      confirmLabel: 'Delete',
      danger: true,
    });
    if (ok) {
      this.api.delete(v.id).subscribe({
        next: () => {
          this.toast.success(`${v.name} deleted`);
          this.toggle(v, false);
          this.reload();
        },
        error: (err: unknown) => this.toast.error(errorMessage(err)),
      });
    }
  }

  protected openHistory(v: Variant): void {
    this.historyOf.set(v);
    this.history.set([]);
    this.historyPage.set(0);
    this.dialog.set('history');
    this.loadHistory();
  }

  protected loadHistory(): void {
    const v = this.historyOf();
    if (!v) return;
    this.api.history(v.id, this.historyPage()).subscribe({
      next: (page) => {
        this.history.update((h) => [...h, ...page.content]);
        this.historyMore.set(page.page + 1 < page.totalPages);
        this.historyPage.set(page.page + 1);
      },
      error: (err: unknown) => this.toast.error(errorMessage(err)),
    });
  }

  protected close(): void {
    if (!this.saving()) this.dialog.set(null);
  }

  private run(request: ReturnType<StockApi['create']> | ReturnType<StockApi['createAll']>, message: string, clearSelection = false): void {
    this.saving.set(true);
    (request as import('rxjs').Observable<unknown>).subscribe({
      next: () => {
        this.saving.set(false);
        this.dialog.set(null);
        this.toast.success(message);
        if (clearSelection) this.clearSelection();
        this.reload();
      },
      error: (err: unknown) => {
        this.saving.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }

  private reload(): void {
    this.list.reload();
    this.summary.reload();
  }
}
