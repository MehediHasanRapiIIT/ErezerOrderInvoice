import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

/** Zero-based pager: "Showing 21–40 of 2,116" plus previous/next and nearby page numbers. */
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
        <div class="flex items-center gap-1">
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

  protected readonly firstRow = computed(() => this.page() * this.size() + 1);
  protected readonly lastRow = computed(() => Math.min((this.page() + 1) * this.size(), this.totalElements()));

  /** Current page ±1 plus first and last, with gaps as null. */
  protected readonly pages = computed<(number | null)[]>(() => {
    const total = this.totalPages();
    const current = this.page();
    const wanted = new Set([0, total - 1, current - 1, current, current + 1].filter((p) => p >= 0 && p < total));
    const sorted = [...wanted].sort((a, b) => a - b);
    const result: (number | null)[] = [];
    sorted.forEach((p, i) => {
      if (i > 0 && p - sorted[i - 1] > 1) {
        result.push(null);
      }
      result.push(p);
    });
    return result;
  });
}
