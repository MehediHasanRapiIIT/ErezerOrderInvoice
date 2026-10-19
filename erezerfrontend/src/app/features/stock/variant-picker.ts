import { ChangeDetectionStrategy, Component, ElementRef, inject, input, model, signal } from '@angular/core';
import { Subject, debounceTime, switchMap, catchError, of } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { StockApi, VariantSuggestion } from './stock.api';

/**
 * The order form's Variant field: typing suggests every stock variant whose name contains the text, and only a
 * variant picked from the list counts (its id is what moves stock). Shows how many are left.
 */
@Component({
  selector: 'app-variant-picker',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(document:click)': 'onDocumentClick($event)' },
  template: `
    <div class="relative">
      <input class="field" [value]="name()" (input)="onInput($any($event.target).value)" (focus)="open()" (keydown)="onKey($event)"
             [placeholder]="placeholder()" autocomplete="off" role="combobox" [attr.aria-expanded]="showList()"
             [attr.aria-label]="label()" [class.field-invalid]="invalid()" />
      @if (variantId() !== null && picked(); as p) {
        <span class="pointer-events-none absolute right-2 top-1/2 -translate-y-1/2 rounded px-1.5 py-0.5 text-[11px]"
              [class]="p.quantity === 0 ? 'bg-red-500/15 text-red-300' : p.quantity <= 5 ? 'bg-amber-500/15 text-amber-300' : 'bg-emerald-500/15 text-emerald-300'">
          {{ p.quantity }} in stock
        </span>
      }
      @if (showList()) {
        <ul class="absolute left-0 right-0 top-full z-30 mt-1 max-h-64 overflow-y-auto rounded-md border border-line bg-panel-raised py-1 shadow-xl" role="listbox">
          @for (s of suggestions(); track s.id; let i = $index) {
            <li role="option" [attr.aria-selected]="i === active()">
              <button type="button" class="flex w-full cursor-pointer items-center justify-between gap-3 px-3 py-2 text-left text-sm"
                      [class.bg-white/10]="i === active()" (mousedown)="$event.preventDefault()" (click)="choose(s)">
                <span class="text-slate-100">{{ s.name }}</span>
                <span class="text-xs" [class]="s.quantity === 0 ? 'text-red-300' : s.quantity <= 5 ? 'text-amber-300' : 'text-slate-400'">
                  {{ s.quantity === 0 ? 'Out of stock' : s.quantity + ' in stock' }}
                </span>
              </button>
            </li>
          } @empty {
            <li class="px-3 py-2 text-sm text-slate-400">No variant contains "{{ name() }}". Add it in Stock first.</li>
          }
        </ul>
      }
      @if (invalid()) {
        <p class="field-error">Choose a variant from the list</p>
      }
    </div>
  `,
})
export class VariantPicker {
  private readonly api = inject(StockApi);
  private readonly host = inject(ElementRef<HTMLElement>);

  /** The variant's name (shown, and saved as the item's colour). */
  readonly name = model('');
  /** The picked variant; null while the text does not match a picked variant. */
  readonly variantId = model<number | null>(null);
  readonly label = input('Variant');
  readonly placeholder = input('Type to search, e.g. Black');

  protected readonly suggestions = signal<VariantSuggestion[]>([]);
  protected readonly showList = signal(false);
  protected readonly active = signal(0);
  protected readonly picked = signal<VariantSuggestion | null>(null);
  protected readonly invalid = () => this.name().trim() !== '' && this.variantId() === null;

  private readonly queries = new Subject<string>();

  constructor() {
    this.queries
      .pipe(
        debounceTime(150),
        switchMap((q) => this.api.suggest(q).pipe(catchError(() => of([] as VariantSuggestion[])))),
        takeUntilDestroyed(),
      )
      .subscribe((list) => {
        this.suggestions.set(list);
        this.active.set(0);
        // Typed an exact name: pick it, so pasting or typing a full name works too.
        const exact = list.find((s) => s.name.toLowerCase() === this.name().trim().toLowerCase());
        if (exact && this.variantId() === null) {
          this.variantId.set(exact.id);
          this.picked.set(exact);
        }
        if (exact && this.variantId() === exact.id) {
          this.picked.set(exact);
        }
      });
  }

  protected open(): void {
    this.showList.set(true);
    this.queries.next(this.name().trim());
  }

  protected onInput(value: string): void {
    this.name.set(value);
    this.variantId.set(null);
    this.picked.set(null);
    this.showList.set(true);
    this.queries.next(value.trim());
  }

  protected choose(s: VariantSuggestion): void {
    this.name.set(s.name);
    this.variantId.set(s.id);
    this.picked.set(s);
    this.showList.set(false);
  }

  protected onKey(event: KeyboardEvent): void {
    const list = this.suggestions();
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.showList.set(true);
      this.active.set(Math.min(this.active() + 1, list.length - 1));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.active.set(Math.max(this.active() - 1, 0));
    } else if (event.key === 'Enter' && this.showList() && list[this.active()]) {
      event.preventDefault();
      this.choose(list[this.active()]);
    } else if (event.key === 'Escape') {
      this.showList.set(false);
    }
  }

  protected onDocumentClick(event: MouseEvent): void {
    if (!this.host.nativeElement.contains(event.target as Node)) {
      this.showList.set(false);
    }
  }
}
