import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

/**
 * Zero-based pager: "Showing 21–40 of 2,116", previous/next, the first and last page, two pages either side of the
 * current one, and on long lists a box to jump straight to any page.
 */
@Component({
  selector: 'app-pagination',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (totalElements() > 0) {
      <nav class="flex flex-wrap items-center justify-between gap-3 text-sm text-slate-400" aria-label="Pagination">
        <p>
          Showing <span class="font-medium text-slate-200">{{ firstRow() }}–{{ lastRow() }}</span> of
          <span class="font-medium text-slate-200">{{ totalElements().toLocaleString() }}</span>
        </p>
        <div class="flex flex-wrap items-center gap-1">
          <button type="button" class="pager-btn" [disabled]="page() === 0" (click)="pageChange.emit(page() - 1)" aria-label="Previous page">
            <i class="fas fa-chevron-left text-xs"></i>
          </button>
          @for (p of pages(); track $index) {
            @if (p === null) {
              <span class="px-1">…</span>
            } @else {
              <button
                type="button"
                class="pager-btn"
                [class.pager-btn-active]="p === page()"
                [attr.aria-current]="p === page() ? 'page' : null"
                (click)="pageChange.emit(p)"
              >
                {{ p + 1 }}
              </button>
            }
          }
          <button type="button" class="pager-btn" [disabled]="page() >= totalPages() - 1" (click)="pageChange.emit(page() + 1)" aria-label="Next page">
            <i class="fas fa-chevron-right text-xs"></i>
          </button>
          @if (totalPages() > JUMP_FROM) {
            <form class="ml-2 flex items-center gap-1" (submit)="$event.preventDefault(); jump(jumpBox.value); jumpBox.value = ''">
              <input #jumpBox type="number" min="1" [max]="totalPages()" inputmode="numeric"
                     class="pager-jump" placeholder="Page" [attr.aria-label]="'Go to page, 1 to ' + totalPages()" />
              <button type="submit" class="pager-btn">Go</button>
            </form>
          }
        </div>
      </nav>
    }
  `,
  styles: `
    .pager-btn {
      min-width: 2rem;
      height: 2rem;
      padding: 0 0.5rem;
      border-radius: 0.375rem;
      border: 1px solid var(--color-line);
      color: var(--color-slate-300);
      cursor: pointer;
    }
    .pager-btn:hover:not(:disabled) {
      background: rgb(255 255 255 / 0.06);
    }
    .pager-btn:disabled {
      opacity: 0.4;
      cursor: not-allowed;
    }
    .pager-jump {
      width: 4rem;
      height: 2rem;
      padding: 0 0.5rem;
      border-radius: 0.375rem;
      border: 1px solid var(--color-line);
      background: transparent;
      color: var(--color-slate-200);
    }
    .pager-btn-active {
      background: var(--color-brand);
      border-color: var(--color-brand);
      color: white;
    }
  `,
})
export class Pagination {
  readonly page = input.required<number>();
  readonly size = input.required<number>();
  readonly totalElements = input.required<number>();
  readonly totalPages = input.required<number>();
  readonly pageChange = output<number>();

  /** Lists with more pages than this get the "go to page" box. */
  protected readonly JUMP_FROM = 7;

  protected readonly firstRow = computed(() => this.page() * this.size() + 1);
  protected readonly lastRow = computed(() => Math.min((this.page() + 1) * this.size(), this.totalElements()));

  /**
   * First and last page plus two either side of the current one (five at the start or end of the list), with gaps
   * as null. A gap of a single page shows that page instead of "…".
   */
  protected readonly pages = computed<(number | null)[]>(() => {
    const total = this.totalPages();
    const current = this.page();
    const from = Math.max(0, Math.min(current - 2, total - 5));
    const to = Math.min(total - 1, Math.max(current + 2, 4));
    const wanted = new Set([0, total - 1]);
    for (let p = from; p <= to; p++) {
      wanted.add(p);
    }
    const sorted = [...wanted].filter((p) => p >= 0 && p < total).sort((a, b) => a - b);
    const result: (number | null)[] = [];
    sorted.forEach((p, i) => {
      const gap = i > 0 ? p - sorted[i - 1] : 1;
      if (gap === 2) {
        result.push(p - 1);
      } else if (gap > 2) {
        result.push(null);
      }
      result.push(p);
    });
    return result;
  });

  /** Goes to a typed page number (1-based), kept within the list. */
  protected jump(value: string): void {
    const typed = Math.trunc(Number(value));
    if (!Number.isFinite(typed) || value.trim() === '') {
      return;
    }
    const target = Math.min(Math.max(typed, 1), this.totalPages()) - 1;
    if (target !== this.page()) {
      this.pageChange.emit(target);
    }
  }
}
