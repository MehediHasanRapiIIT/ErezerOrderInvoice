import { ChangeDetectionStrategy, Component, computed, ElementRef, forwardRef, inject, input, signal } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const SHORT_MONTHS = MONTHS.map((m) => m.slice(0, 3));
const WEEKDAYS = ['Sa', 'Su', 'Mo', 'Tu', 'We', 'Th', 'Fr'];

/** Today's date in Bangladesh ("2026-10-26"). */
function bdToday(): string {
  return new Date(Date.now() + 6 * 3_600_000).toISOString().slice(0, 10);
}

function shiftDay(date: string, days: number): string {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/**
 * A quick date (and time) field: one click on a day picks it, with Yesterday / Today / Tomorrow shortcuts and a
 * plain time box. The value is "YYYY-MM-DDTHH:mm" in Bangladesh time, like a datetime-local input.
 */
@Component({
  selector: 'app-date-time-picker',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => DateTimePicker), multi: true }],
  host: { '(document:click)': 'onDocumentClick($event)', '(keydown.escape)': 'open.set(false)' },
  template: `
    <div class="relative">
      <div class="flex gap-2">
        <button type="button" class="field flex flex-1 cursor-pointer items-center justify-between text-left" (click)="toggle()"
                [disabled]="disabled()" [attr.aria-label]="label()" aria-haspopup="dialog" [attr.aria-expanded]="open()">
          <span>{{ dateLabel() }}</span>
          <i class="fas fa-calendar-alt text-slate-400"></i>
        </button>
        @if (showTime()) {
          <input type="time" class="field w-32" [value]="time()" (change)="setTime($any($event.target).value)" [disabled]="disabled()"
                 aria-label="Time" />
        }
      </div>

      @if (open() || staticCalendar()) {
        <div class="w-72 max-w-full rounded-lg border border-line bg-panel-raised p-3" [class]="staticCalendar() ? 'mt-2' : 'absolute left-0 top-full z-40 mt-1 shadow-2xl'"
             role="dialog" aria-label="Choose a date">
          <div class="mb-2 flex gap-1.5">
            <button type="button" class="flex-1 cursor-pointer rounded-md bg-white/5 px-2 py-1 text-xs text-slate-200 hover:bg-white/10" (click)="pick(shift(-1))">Yesterday</button>
            <button type="button" class="flex-1 cursor-pointer rounded-md bg-brand/20 px-2 py-1 text-xs font-semibold text-brand-light hover:bg-brand/30" (click)="pick(today)">Today</button>
            <button type="button" class="flex-1 cursor-pointer rounded-md bg-white/5 px-2 py-1 text-xs text-slate-200 hover:bg-white/10" (click)="pick(shift(1))">Tomorrow</button>
          </div>
          <div class="mb-1 flex items-center justify-between">
            <button type="button" class="cursor-pointer rounded p-1.5 text-slate-400 hover:bg-white/5 hover:text-white" (click)="moveMonth(-1)" aria-label="Previous month"><i class="fas fa-chevron-left text-xs"></i></button>
            <span class="text-sm font-semibold text-white">{{ monthTitle() }}</span>
            <button type="button" class="cursor-pointer rounded p-1.5 text-slate-400 hover:bg-white/5 hover:text-white" (click)="moveMonth(1)" aria-label="Next month"><i class="fas fa-chevron-right text-xs"></i></button>
          </div>
          <div class="grid grid-cols-7 text-center text-[11px] text-slate-500">
            @for (d of weekdays; track d) { <span class="py-1">{{ d }}</span> }
          </div>
          <div class="grid grid-cols-7 gap-0.5 text-center text-sm">
            @for (cell of cells(); track $index) {
              @if (cell) {
                <button type="button" class="cursor-pointer rounded-md py-1.5"
                        [class]="cell === date() ? 'bg-brand font-semibold text-white' : cell === today ? 'text-brand-light ring-1 ring-brand/60 hover:bg-white/10' : 'text-slate-200 hover:bg-white/10'"
                        (click)="pick(cell)">{{ +cell.slice(8) }}</button>
              } @else {
                <span></span>
              }
            }
          </div>
        </div>
      }
    </div>
  `,
})
export class DateTimePicker implements ControlValueAccessor {
  private readonly host = inject(ElementRef<HTMLElement>);

  readonly label = input('Date');
  /** Show the time box next to the date. */
  readonly showTime = input(true);
  /** Show the calendar always, in place (for tight spots such as a table row, where a pop-up would be cut off). */
  readonly staticCalendar = input(false);

  protected readonly weekdays = WEEKDAYS;
  protected readonly today = bdToday();
  protected readonly date = signal(this.today);
  protected readonly time = signal('12:00');
  protected readonly open = signal(false);
  protected readonly disabled = signal(false);
  /** First day of the month shown ("2026-10-01"). */
  private readonly month = signal(this.today.slice(0, 8) + '01');

  protected readonly dateLabel = computed(() => {
    const [y, m, d] = this.date().split('-').map(Number);
    const relative = this.date() === this.today ? 'Today · ' : this.date() === shiftDay(this.today, -1) ? 'Yesterday · ' : this.date() === shiftDay(this.today, 1) ? 'Tomorrow · ' : '';
    return `${relative}${d} ${SHORT_MONTHS[m - 1]} ${y}`;
  });
  protected readonly monthTitle = computed(() => {
    const [y, m] = this.month().split('-').map(Number);
    return `${MONTHS[m - 1]} ${y}`;
  });
  /** The month grid, weeks starting on Saturday (as in Bangladesh); null = blank cell. */
  protected readonly cells = computed(() => {
    const first = new Date(`${this.month()}T00:00:00Z`);
    const lead = (first.getUTCDay() + 1) % 7; // Saturday = 0
    const days = new Date(Date.UTC(first.getUTCFullYear(), first.getUTCMonth() + 1, 0)).getUTCDate();
    const cells: (string | null)[] = Array.from({ length: lead }, () => null);
    for (let d = 1; d <= days; d++) {
      cells.push(`${this.month().slice(0, 8)}${String(d).padStart(2, '0')}`);
    }
    return cells;
  });

  private onChange: (value: string) => void = () => undefined;
  private onTouched: () => void = () => undefined;

  writeValue(value: string | null): void {
    const v = value || `${this.today}T${new Date(Date.now() + 6 * 3_600_000).toISOString().slice(11, 16)}`;
    this.date.set(v.slice(0, 10));
    this.time.set(v.slice(11, 16) || '12:00');
    this.month.set(v.slice(0, 8) + '01');
  }

  registerOnChange(fn: (value: string) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(disabled: boolean): void {
    this.disabled.set(disabled);
  }

  protected toggle(): void {
    if (!this.open()) this.month.set(this.date().slice(0, 8) + '01');
    this.open.set(!this.open());
  }

  protected shift(days: number): string {
    return shiftDay(this.today, days);
  }

  protected pick(date: string): void {
    this.date.set(date);
    this.month.set(date.slice(0, 8) + '01');
    this.open.set(false);
    this.emit();
  }

  protected setTime(value: string): void {
    if (!value) return;
    this.time.set(value);
    this.emit();
  }

  protected moveMonth(by: number): void {
    const d = new Date(`${this.month()}T00:00:00Z`);
    d.setUTCMonth(d.getUTCMonth() + by);
    this.month.set(d.toISOString().slice(0, 8) + '01');
  }

  protected onDocumentClick(event: MouseEvent): void {
    if (this.open() && !this.host.nativeElement.contains(event.target as Node)) {
      this.open.set(false);
      this.onTouched();
    }
  }

  private emit(): void {
    this.onChange(`${this.date()}T${this.time()}`);
    this.onTouched();
  }
}
