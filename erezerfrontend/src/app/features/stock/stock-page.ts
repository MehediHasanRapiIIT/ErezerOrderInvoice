import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { errorMessage } from '../../core/http/api-error';
import { ToastService } from '../../core/notify/toast.service';
import { HasPermissionDirective } from '../../shared/has-permission.directive';
import { BdDatePipe } from '../../shared/ui/bd-date.pipe';
import { ConfirmService } from '../../shared/ui/confirm-dialog';
import { Pagination } from '../../shared/ui/pagination';
import { Size, SizeQuantity, StockApi, StockMode, StockMovement, Variant, VariantSize } from './stock.api';
import { VariantPicker } from './variant-picker';

const PAGE_SIZE = 20;

type Dialog = 'add' | 'bulk-add' | 'edit' | 'bulk-update' | 'history' | 'merge' | 'sizes' | null;

/** A size column of the grid. */
interface Column {
  sizeId: number;
  name: string;
  legacy: boolean;
}

/** Quantities typed per size id; an empty box means the variant doesn't come in that size. */
type SizeBoxes = Record<number, number | null | ''>;

interface NewRow {
  name: string;
  qty: SizeBoxes;
}

interface UpdateRow {
  variant: Variant;
  size: VariantSize;
  mode: StockMode;
  amount: number | null;
}

/**
 * Stock: variants × sizes. Add and update one at a time or in bulk, merge old "Name - Size" variants into sizes,
 * and see every change. Admins also manage the size list here.
 */
@Component({
  selector: 'app-stock-page',
  imports: [FormsModule, RouterLink, BdDatePipe, HasPermissionDirective, Pagination, VariantPicker],
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
  protected readonly sizeList = rxResource({ stream: () => this.api.sizes() });
  protected readonly rows = computed(() => (this.list.hasValue() ? this.list.value().content : []));
  protected readonly allSizes = computed<Size[]>(() => (this.sizeList.hasValue() ? this.sizeList.value() : []));
  protected readonly activeSizes = computed(() => this.allSizes().filter((s) => s.active));

  /** Grid columns: switched-on sizes plus any other size a listed variant still has; "No size" last when present. */
  protected readonly columns = computed<Column[]>(() => {
    const used = new Set(this.rows().flatMap((v) => v.sizes.map((s) => s.sizeId)));
    const cols: Column[] = this.allSizes()
      .filter((s) => s.active || used.has(s.id))
      .map((s) => ({ sizeId: s.id, name: s.name, legacy: false }));
    const legacy = this.rows().flatMap((v) => v.sizes).find((s) => s.legacy);
    if (legacy) cols.push({ sizeId: legacy.sizeId, name: 'No size', legacy: true });
    return cols;
  });

  // Selection for bulk update (kept across pages).
  protected readonly selected = signal<Map<number, Variant>>(new Map());
  protected readonly allOnPageSelected = computed(() => this.rows().length > 0 && this.rows().every((r) => this.selected().has(r.id)));

  protected readonly dialog = signal<Dialog>(null);
  protected readonly saving = signal(false);

  // Add one
  protected addName = '';
  protected addQty: SizeBoxes = {};
  // Add many
  protected readonly newRows = signal<NewRow[]>([]);
  // Edit one
  protected readonly editing = signal<Variant | null>(null);
  protected editName = '';
  protected editQty: SizeBoxes = {};
  protected editNote = '';
  // Update many
  protected readonly updateRows = signal<UpdateRow[]>([]);
  protected updateNote = '';
  // Merge
  protected readonly mergeSource = signal<Variant | null>(null);
  protected mergeTargetName = '';
  protected mergeTargetId: number | null = null;
  protected mergeSizeId: number | null = null;
  // History
  protected readonly historyOf = signal<Variant | null>(null);
  protected readonly history = signal<StockMovement[]>([]);
  protected readonly historyPage = signal(0);
  protected readonly historyMore = signal(false);
  // Sizes
  protected newSizeName = '';
  protected readonly renamingSize = signal<number | null>(null);

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

  protected readonly isLegacy = (s: VariantSize): boolean => s.legacy;

  protected sizeLabel(id: number | null): string {
    return this.allSizes().find((s) => s.id === id)?.name ?? '';
  }

  protected cell(v: Variant, sizeId: number): VariantSize | undefined {
    return v.sizes.find((s) => s.sizeId === sizeId);
  }

  protected qtyClass(quantity: number, low: boolean): string {
    return quantity === 0 ? 'bg-red-500/15 text-red-300' : low ? 'bg-amber-500/15 text-amber-300' : 'bg-emerald-500/10 text-emerald-300';
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
    this.addQty = {};
    this.dialog.set('add');
  }

  protected saveAdd(): void {
    const name = this.addName.trim();
    const sizes = this.toSizes(this.addQty);
    if (!name) {
      this.toast.error('Enter the variant name');
      return;
    }
    if (sizes === null) return;
    if (!sizes.length) {
      this.toast.error('Enter a quantity (0 or more) for each size it comes in');
      return;
    }
    this.run(this.api.create(name, sizes), `${name} added`);
  }

  protected openBulkAdd(): void {
    this.newRows.set(Array.from({ length: 5 }, () => ({ name: '', qty: {} })));
    this.dialog.set('bulk-add');
  }

  protected addRow(): void {
    this.newRows.update((rows) => [...rows, { name: '', qty: {} }]);
  }

  protected removeRow(index: number): void {
    this.newRows.update((rows) => rows.filter((_, i) => i !== index));
  }

  protected setNewName(index: number, name: string): void {
    this.newRows.update((rows) => rows.map((r, i) => (i === index ? { ...r, name } : r)));
  }

  protected setNewQty(index: number, sizeId: number, value: number | null | ''): void {
    this.newRows.update((rows) => rows.map((r, i) => (i === index ? { ...r, qty: { ...r.qty, [sizeId]: value } } : r)));
  }

  protected readonly filledRows = computed(() => this.newRows().filter((r) => r.name.trim() !== ''));

  protected saveBulkAdd(): void {
    const rows = this.filledRows();
    if (!rows.length) {
      this.toast.error('Enter at least one variant name');
      return;
    }
    const items: { name: string; sizes: SizeQuantity[] }[] = [];
    for (const r of rows) {
      const sizes = this.toSizes(r.qty);
      if (sizes === null) return;
      if (!sizes.length) {
        this.toast.error(`${r.name.trim()}: enter a quantity for each size it comes in`);
        return;
      }
      items.push({ name: r.name.trim(), sizes });
    }
    this.run(this.api.createAll(items), `${rows.length} variant${rows.length === 1 ? '' : 's'} added`);
  }

  // ---- Update ----

  /** Sizes offered when editing: every switched-on size plus whatever the variant already has. */
  protected editSizes(): Column[] {
    const v = this.editing();
    if (!v) return [];
    const own = new Set(v.sizes.map((s) => s.sizeId));
    const cols: Column[] = this.allSizes()
      .filter((s) => s.active || own.has(s.id))
      .map((s) => ({ sizeId: s.id, name: s.name, legacy: false }));
    const legacy = v.sizes.find((s) => s.legacy);
    if (legacy) cols.push({ sizeId: legacy.sizeId, name: 'No size', legacy: true });
    return cols;
  }

  protected openEdit(v: Variant): void {
    this.editing.set(v);
    this.editName = v.name;
    this.editQty = Object.fromEntries(v.sizes.map((s) => [s.sizeId, s.quantity]));
    this.editNote = '';
    this.dialog.set('edit');
  }

  protected current(sizeId: number): number | null {
    return this.editing()?.sizes.find((s) => s.sizeId === sizeId)?.quantity ?? null;
  }

  protected editChange(sizeId: number): number {
    const value = this.editQty[sizeId];
    const now = this.current(sizeId) ?? 0;
    return value === null || value === '' || value === undefined ? -now : Number(value) - now;
  }

  protected editTotal(): number {
    return Object.values(this.editQty).reduce<number>((sum, q) => sum + (q === null || q === '' ? 0 : Number(q) || 0), 0);
  }

  protected saveEdit(): void {
    const v = this.editing();
    if (!v || !this.editName.trim()) {
      this.toast.error('Enter the variant name');
      return;
    }
    const sizes = this.toSizes(this.editQty);
    if (sizes === null) return;
    const dropped = v.sizes.filter((s) => s.quantity > 0 && !sizes.some((q) => q.sizeId === s.sizeId));
    if (dropped.length) {
      this.toast.error(`Set ${dropped.map((s) => s.size).join(', ')} to 0 before taking ${dropped.length === 1 ? 'it' : 'them'} off`);
      return;
    }
    this.run(this.api.update(v.id, { name: this.editName.trim(), sizes, note: this.editNote.trim() || null, version: v.version }),
      `${this.editName.trim()} updated`);
  }

  protected openBulkUpdate(): void {
    this.updateRows.set(
      [...this.selected().values()].flatMap((variant) => variant.sizes.map((size) => ({ variant, size, mode: 'ADD' as StockMode, amount: null }))),
    );
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
    return r.mode === 'SET' ? amount : r.mode === 'ADD' ? r.size.quantity + amount : r.size.quantity - amount;
  }

  protected saveBulkUpdate(): void {
    const rows = this.updateRows().filter((r) => r.amount !== null && `${r.amount}` !== '');
    if (!rows.length) {
      this.toast.error('Enter an amount for at least one size');
      return;
    }
    if (rows.some((r) => !Number.isInteger(Number(r.amount)) || Number(r.amount) < 0 || this.newQuantity(r) < 0)) {
      this.toast.error('Amounts must be whole numbers, and stock cannot go below 0');
      return;
    }
    this.run(
      this.api.updateAll(rows.map((r) => ({ stockItemId: r.size.stockItemId, mode: r.mode, amount: Number(r.amount) })), this.updateNote.trim() || null),
      `${rows.length} size${rows.length === 1 ? '' : 's'} updated`,
      true,
    );
  }

  // ---- Merge ----

  /** Only a variant with at most one size can be merged into another variant's size. */
  protected canMerge(v: Variant): boolean {
    return v.sizes.length <= 1;
  }

  protected openMerge(v: Variant): void {
    this.mergeSource.set(v);
    this.mergeTargetName = '';
    this.mergeTargetId = null;
    // A name like "Navy Blue - M" suggests the target and the size.
    const match = /^(.*?)\s*[-–/]\s*([^-–/]+)$/.exec(v.name);
    const size = match ? this.activeSizes().find((s) => s.name.toLowerCase() === match[2].trim().toLowerCase()) : undefined;
    this.mergeSizeId = size?.id ?? null;
    if (match && size) this.mergeTargetName = match[1].trim();
    this.dialog.set('merge');
  }

  protected saveMerge(): void {
    const v = this.mergeSource();
    if (!v || this.mergeTargetId === null || this.mergeSizeId === null) {
      this.toast.error('Choose the variant and the size to merge into');
      return;
    }
    if (this.mergeTargetId === v.id) {
      this.toast.error('Choose a different variant');
      return;
    }
    const size = this.allSizes().find((s) => s.id === this.mergeSizeId)?.name;
    this.run(this.api.merge(v.id, this.mergeTargetId, this.mergeSizeId), `${v.name} merged into ${this.mergeTargetName} / ${size}`);
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

  // ---- Sizes (admin) ----

  protected openSizes(): void {
    this.newSizeName = '';
    this.renamingSize.set(null);
    this.dialog.set('sizes');
  }

  protected addSize(): void {
    const name = this.newSizeName.trim();
    if (!name) return;
    this.sizeCall(this.api.createSize(name), `Size ${name} added`, () => (this.newSizeName = ''));
  }

  protected renameSize(s: Size, name: string): void {
    if (!name.trim() || name.trim() === s.name) {
      this.renamingSize.set(null);
      return;
    }
    this.sizeCall(this.api.updateSize(s.id, name.trim(), s.active), 'Size renamed', () => this.renamingSize.set(null));
  }

  protected toggleSize(s: Size): void {
    this.sizeCall(this.api.updateSize(s.id, s.name, !s.active), s.active ? `${s.name} switched off` : `${s.name} switched on`);
  }

  protected moveSize(index: number, by: -1 | 1): void {
    const ids = this.allSizes().map((s) => s.id);
    const target = index + by;
    if (target < 0 || target >= ids.length) return;
    [ids[index], ids[target]] = [ids[target], ids[index]];
    this.sizeCall(this.api.reorderSizes(ids));
  }

  protected removeSize(s: Size): void {
    this.sizeCall(this.api.deleteSize(s.id), `Size ${s.name} deleted`);
  }

  protected close(): void {
    if (!this.saving()) this.dialog.set(null);
  }

  /** Boxes → sizes to send; null (after a message) when a box isn't a whole number ≥ 0. */
  private toSizes(boxes: SizeBoxes): SizeQuantity[] | null {
    const sizes: SizeQuantity[] = [];
    for (const [id, value] of Object.entries(boxes)) {
      if (value === null || value === '' || value === undefined) continue;
      const quantity = Number(value);
      if (!Number.isInteger(quantity) || quantity < 0) {
        this.toast.error('Quantities must be whole numbers, 0 or more');
        return null;
      }
      sizes.push({ sizeId: Number(id), quantity });
    }
    return sizes;
  }

  private sizeCall(request: Observable<Size[]>, message?: string, after?: () => void): void {
    this.saving.set(true);
    request.subscribe({
      next: (list) => {
        this.saving.set(false);
        this.sizeList.set(list);
        if (message) this.toast.success(message);
        after?.();
        this.list.reload();
      },
      error: (err: unknown) => {
        this.saving.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }

  private run(request: Observable<unknown>, message: string, clearSelection = false): void {
    this.saving.set(true);
    request.subscribe({
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
    this.sizeList.reload();
  }
}
