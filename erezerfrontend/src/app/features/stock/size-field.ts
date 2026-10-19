import { ChangeDetectionStrategy, Component, computed, effect, inject, input, model, signal, untracked } from '@angular/core';
import { catchError, of } from 'rxjs';
import { StockApi, VariantSize } from './stock.api';

/**
 * The Size field of an order item. With a stock variant picked it is a list of that variant's sizes and how many are
 * left (its stock item is what moves stock); without one it is free text, as before sizes were tracked.
 */
@Component({
  selector: 'app-size-field',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (variantId() === null) {
      <input class="field" [value]="size()" (input)="size.set($any($event.target).value)" placeholder="L" maxlength="30"
             [attr.list]="listId()" [attr.aria-label]="label()" />
      <datalist [id]="listId()">
        @for (s of suggestions(); track s) { <option [value]="s"></option> }
      </datalist>
    } @else {
      <select class="field" [class.field-invalid]="missing()" (change)="choose(+$any($event.target).value)" [attr.aria-label]="label()"
              [disabled]="loading()">
        <option value="" [selected]="stockItemId() === null" disabled>{{ loading() ? 'Loading…' : 'Choose' }}</option>
        @for (o of options(); track o.stockItemId) {
          <option [value]="o.stockItemId" [selected]="o.stockItemId === stockItemId()"
                  [disabled]="o.quantity === 0 && o.stockItemId !== stockItemId()">
            {{ o.size }} · {{ o.quantity === 0 ? 'out of stock' : o.quantity + ' left' }}
          </option>
        }
      </select>
      @if (missing()) {
        <p class="field-error">{{ options().length ? 'Choose a size' : 'No sizes in stock' }}</p>
      }
    }
  `,
})
export class SizeField {
  private readonly api = inject(StockApi);

  readonly variantId = input<number | null>(null);
  /** The picked variant + size; null while none is chosen. */
  readonly stockItemId = model<number | null>(null);
  /** The size text saved on the item (free text, or the chosen size's name). */
  readonly size = model('');
  readonly suggestions = input<readonly string[]>([]);
  readonly listId = input('sizes');
  readonly label = input('Size');

  protected readonly sizes = signal<VariantSize[]>([]);
  protected readonly loading = signal(false);
  /** Sizes to offer; a size the item already has stays listed even if it was taken off the variant since. */
  protected readonly options = computed(() => {
    const list = this.sizes();
    const current = this.stockItemId();
    if (current !== null && !this.loading() && !list.some((s) => s.stockItemId === current)) {
      return [...list, { stockItemId: current, sizeId: 0, size: this.size() || 'Current size', legacy: false, quantity: 0, low: true }];
    }
    return list;
  });
  protected readonly missing = computed(() => this.variantId() !== null && !this.loading() && this.stockItemId() === null);

  private previousVariant: number | null | undefined = undefined;

  constructor() {
    effect(() => {
      const variantId = this.variantId();
      untracked(() => this.load(variantId));
    });
  }

  protected choose(stockItemId: number): void {
    const option = this.options().find((o) => o.stockItemId === stockItemId);
    this.stockItemId.set(stockItemId);
    if (option && option.sizeId !== 0) {
      this.size.set(option.legacy ? '' : option.size);
    }
  }

  private load(variantId: number | null): void {
    // Picking a different variant clears the size; the first load (an order being edited) keeps it.
    if (this.previousVariant !== undefined && this.previousVariant !== variantId) {
      this.stockItemId.set(null);
    }
    this.previousVariant = variantId;
    this.sizes.set([]);
    if (variantId === null) {
      return;
    }
    this.loading.set(true);
    this.api
      .sizesOf(variantId)
      .pipe(catchError(() => of([] as VariantSize[])))
      .subscribe((list) => {
        if (this.variantId() !== variantId) return;
        this.sizes.set(list);
        this.loading.set(false);
        // A variant with one size needs no choice.
        if (this.stockItemId() === null && list.length === 1) {
          this.choose(list[0].stockItemId);
        }
      });
  }
}
