import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  input,
  signal,
  viewChild,
  afterNextRender,
} from '@angular/core';
import { TakaPipe } from '../../shared/ui/taka.pipe';
import { SeriesPoint } from './dashboard.models';

/** Categorical slots 1 and 2, dark steps, validated against the panel surface (#141d31). */
const REVENUE_COLOR = '#3987e5';
const EXPENSE_COLOR = '#d95926';
const HEIGHT = 260;
const MARGIN = { top: 18, right: 8, bottom: 28, left: 56 };
const BAR_GAP = 2; // surface gap between the two bars of a bucket

interface Bar {
  x: number;
  y: number;
  width: number;
  height: number;
  path: string;
}

/**
 * Grouped bars: product revenue vs expenses per day or month, on one shared ৳ axis.
 * Hovering a bucket shows its values; a table view gives the same data as text.
 */
@Component({
  selector: 'app-revenue-chart',
  imports: [TakaPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div #host>
    <div class="mb-3 flex flex-wrap items-center justify-between gap-3">
      <div class="flex items-center gap-4 text-xs text-slate-300" aria-hidden="true">
        <span class="flex items-center gap-1.5"><span class="h-2.5 w-2.5 rounded-sm" [style.background]="revenueColor"></span>Product revenue</span>
        <span class="flex items-center gap-1.5"><span class="h-2.5 w-2.5 rounded-sm" [style.background]="expenseColor"></span>Expenses</span>
      </div>
      <button type="button" class="cursor-pointer text-xs text-slate-400 hover:text-white" (click)="showTable.set(!showTable())">
        <i class="fas mr-1" [class.fa-table]="!showTable()" [class.fa-chart-bar]="showTable()"></i>{{ showTable() ? 'Show chart' : 'Show table' }}
      </button>
    </div>

    @if (showTable()) {
      <div class="max-h-72 overflow-auto rounded border border-line">
        <table class="w-full text-sm">
          <thead class="sticky top-0 bg-panel text-xs uppercase text-slate-400">
            <tr><th class="px-3 py-2 text-left">{{ granularity() === 'DAY' ? 'Day' : 'Month' }}</th><th class="px-3 py-2 text-right">Product revenue</th><th class="px-3 py-2 text-right">Expenses</th><th class="px-3 py-2 text-right">Difference</th></tr>
          </thead>
          <tbody class="divide-y divide-line text-slate-200">
            @for (p of points(); track p.bucket) {
              <tr>
                <td class="px-3 py-1.5">{{ label(p.bucket, true) }}</td>
                <td class="px-3 py-1.5 text-right">{{ p.productRevenue | taka }}</td>
                <td class="px-3 py-1.5 text-right">{{ p.expenses | taka }}</td>
                <td class="px-3 py-1.5 text-right">{{ p.productRevenue - p.expenses | taka }}</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    } @else {
      <div class="relative w-full" [style.height.px]="height">
        @if (width() > 0) {
          <svg [attr.width]="width()" [attr.height]="height" role="img" [attr.aria-label]="ariaLabel()">
            <!-- Recessive grid and y-axis labels -->
            @for (t of ticks(); track t.value) {
              <line [attr.x1]="margin.left" [attr.x2]="width() - margin.right" [attr.y1]="t.y" [attr.y2]="t.y" stroke="#26324d" stroke-width="1" />
              <text [attr.x]="margin.left - 8" [attr.y]="t.y" dy="0.32em" text-anchor="end" fill="#94a3b8" font-size="11">{{ t.label }}</text>
            }
            <!-- Bars -->
            @for (b of bars(); track $index) {
              @if (hover() === $index) {
                <rect [attr.x]="b.bandX" [attr.y]="margin.top" [attr.width]="b.bandWidth" [attr.height]="plotHeight" fill="#ffffff" fill-opacity="0.04" />
              }
              @if (b.revenue.height > 0) {
                <path [attr.d]="b.revenue.path" [attr.fill]="revenueColor" />
              }
              @if (b.expense.height > 0) {
                <path [attr.d]="b.expense.path" [attr.fill]="expenseColor" />
              }
            }
            <!-- Baseline -->
            <line [attr.x1]="margin.left" [attr.x2]="width() - margin.right" [attr.y1]="baseline()" [attr.y2]="baseline()" stroke="#475569" stroke-width="1" />
            <!-- X labels, thinned so they never collide -->
            @for (b of bars(); track $index) {
              @if ($index % labelEvery() === 0) {
                <text [attr.x]="b.bandX + b.bandWidth / 2" [attr.y]="height - 8" text-anchor="middle" fill="#94a3b8" font-size="11">{{ label(b.point.bucket) }}</text>
              }
            }
            <!-- Hit targets: the whole band, larger than the bars -->
            @for (b of bars(); track $index) {
              <rect [attr.x]="b.bandX" [attr.y]="margin.top" [attr.width]="b.bandWidth" [attr.height]="plotHeight" fill="transparent"
                    (mouseenter)="hover.set($index)" (mouseleave)="hover.set(null)" />
            }
          </svg>

          @if (hovered(); as h) {
            <div class="pointer-events-none absolute z-10 w-48 rounded-md border border-line bg-panel-raised px-3 py-2 text-xs shadow-xl"
                 [style.left.px]="h.left" [style.top.px]="8">
              <p class="mb-1 font-semibold text-white">{{ label(h.point.bucket, true) }}</p>
              <p class="flex justify-between text-slate-300"><span><span class="mr-1.5 inline-block h-2 w-2 rounded-sm" [style.background]="revenueColor"></span>Revenue</span><span class="text-white">{{ h.point.productRevenue | taka }}</span></p>
              <p class="flex justify-between text-slate-300"><span><span class="mr-1.5 inline-block h-2 w-2 rounded-sm" [style.background]="expenseColor"></span>Expenses</span><span class="text-white">{{ h.point.expenses | taka }}</span></p>
              <p class="mt-1 flex justify-between border-t border-line pt-1 text-slate-400"><span>Difference</span><span class="text-white">{{ h.point.productRevenue - h.point.expenses | taka }}</span></p>
            </div>
          }
        }
        @if (isEmpty()) {
          <p class="absolute inset-0 flex items-center justify-center text-sm text-slate-500">No sales or expenses in this period</p>
        }
      </div>
    }
    </div>
  `,
})
export class RevenueChart {
  readonly points = input.required<SeriesPoint[]>();
  readonly granularity = input.required<'DAY' | 'MONTH'>();

  protected readonly revenueColor = REVENUE_COLOR;
  protected readonly expenseColor = EXPENSE_COLOR;
  protected readonly height = HEIGHT;
  protected readonly margin = MARGIN;
  protected readonly plotHeight = HEIGHT - MARGIN.top - MARGIN.bottom;
  protected readonly showTable = signal(false);
  protected readonly hover = signal<number | null>(null);
  protected readonly width = signal(0);

  private readonly host = viewChild<ElementRef<HTMLElement>>('host');

  protected readonly maxValue = computed(() =>
    Math.max(0, ...this.points().flatMap((p) => [p.productRevenue, p.expenses])),
  );
  protected readonly isEmpty = computed(() => this.maxValue() === 0);

  /** Round tick steps (1, 2, 2.5 or 5 × 10ⁿ) so labels read ৳0, ৳2k, ৳4k… rather than ৳1.3k, ৳3.8k. */
  protected readonly axis = computed(() => niceAxis(this.maxValue()));

  protected readonly ticks = computed(() => {
    const { top, step } = this.axis();
    const ticks = [];
    for (let v = 0; v <= top + step / 2; v += step) {
      ticks.push({ value: v, y: MARGIN.top + this.plotHeight * (1 - (top === 0 ? 0 : v / top)), label: compactTaka(v) });
    }
    return ticks;
  });

  protected readonly baseline = computed(() => MARGIN.top + this.plotHeight);

  protected readonly bars = computed(() => {
    const points = this.points();
    const plotWidth = Math.max(0, this.width() - MARGIN.left - MARGIN.right);
    const band = points.length ? plotWidth / points.length : 0;
    const barWidth = Math.max(2, Math.min(14, (band * 0.7 - BAR_GAP) / 2));
    const top = this.axis().top;
    const base = this.baseline();
    const scale = (v: number) => (top === 0 ? 0 : (v / top) * this.plotHeight);

    return points.map((point, i) => {
      const bandX = MARGIN.left + i * band;
      const groupX = bandX + (band - (barWidth * 2 + BAR_GAP)) / 2;
      return {
        point,
        bandX,
        bandWidth: band,
        revenue: bar(groupX, base, barWidth, scale(point.productRevenue)),
        expense: bar(groupX + barWidth + BAR_GAP, base, barWidth, scale(point.expenses)),
      };
    });
  });

  /** Show at most ~8 x labels. */
  protected readonly labelEvery = computed(() => Math.max(1, Math.ceil(this.points().length / 8)));

  protected readonly hovered = computed(() => {
    const index = this.hover();
    if (index === null) {
      return null;
    }
    const b = this.bars()[index];
    if (!b) {
      return null;
    }
    const center = b.bandX + b.bandWidth / 2;
    // Keep the tooltip inside the chart.
    const left = Math.min(Math.max(center - 96, 0), Math.max(this.width() - 192, 0));
    return { point: b.point, left };
  });

  protected readonly ariaLabel = computed(
    () => `Product revenue and expenses per ${this.granularity() === 'DAY' ? 'day' : 'month'}. Use "Show table" for the values.`,
  );

  constructor() {
    const destroyRef = inject(DestroyRef);
    afterNextRender(() => {
      const el = this.host()?.nativeElement;
      if (!el) {
        return;
      }
      const observer = new ResizeObserver(() => this.measure());
      observer.observe(el);
      this.measure();
      destroyRef.onDestroy(() => observer.disconnect());
    });
  }

  protected label(bucket: string, long = false): string {
    const [y, m, d] = bucket.split('-').map(Number);
    const date = new Date(y, m - 1, d);
    if (this.granularity() === 'MONTH') {
      return date.toLocaleDateString('en-GB', { month: long ? 'long' : 'short', year: long ? 'numeric' : '2-digit' });
    }
    return date.toLocaleDateString('en-GB', long ? { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' } : { day: 'numeric', month: 'short' });
  }

  private measure(): void {
    this.width.set(this.host()?.nativeElement.clientWidth ?? 0);
  }
}

/** Bar with a 4px rounded data end, anchored square to the baseline. */
function bar(x: number, base: number, width: number, height: number): Bar {
  const r = Math.min(4, width / 2, height);
  const y = base - height;
  const path = height <= 0 ? ''
    : `M${x},${base} V${y + r} Q${x},${y} ${x + r},${y} H${x + width - r} Q${x + width},${y} ${x + width},${y + r} V${base} Z`;
  return { x, y, width, height, path };
}

/** Axis top and step for at most ~5 gridlines, with every tick a round number. */
export function niceAxis(max: number): { top: number; step: number } {
  if (max <= 0) {
    return { top: 0, step: 1 };
  }
  const rough = max / 4;
  const magnitude = 10 ** Math.floor(Math.log10(rough));
  const step = ([1, 2, 2.5, 5, 10].find((f) => f * magnitude >= rough) ?? 10) * magnitude;
  return { top: Math.ceil(max / step) * step, step };
}

function compactTaka(value: number): string {
  if (value >= 10_000_000) return `৳${+(value / 10_000_000).toFixed(1)}Cr`;
  if (value >= 100_000) return `৳${+(value / 100_000).toFixed(1)}L`;
  if (value >= 1_000) return `৳${+(value / 1_000).toFixed(1)}k`;
  return `৳${Math.round(value)}`;
}
