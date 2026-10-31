import { ChangeDetectionStrategy, Component, computed, inject, input, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { readBlobError } from '../../core/http/api-error';
import { fileNameFrom, saveBlob } from '../../core/http/file-download';
import { ToastService } from '../../core/notify/toast.service';
import { BdDatePipe } from '../../shared/ui/bd-date.pipe';
import { Pagination } from '../../shared/ui/pagination';
import { TakaPipe } from '../../shared/ui/taka.pipe';
import { AttendanceApi } from './data/attendance.api';
import { DAY_STATUS_CLASSES, DAY_STATUS_CODES, DAY_STATUS_LABELS, hoursLabel } from './data/attendance.models';

const PAGE_SIZE = 20;

/** One row per employee and one column per day, with the month's totals and late fees. */
@Component({
  selector: 'app-attendance-month-tab',
  imports: [BdDatePipe, Pagination, TakaPipe],
  template: `
    <section class="card overflow-hidden">
      <div class="flex flex-wrap items-center justify-end gap-3 border-b border-line p-4">
        <button type="button" class="btn-secondary" (click)="exportExcel()" [disabled]="exporting()">
          <i class="fas" [class.fa-file-excel]="!exporting()" [class.text-emerald-400]="!exporting()" [class.fa-circle-notch]="exporting()" [class.fa-spin]="exporting()"></i> Export
        </button>
      </div>
      <div class="overflow-x-auto">
        <table class="w-full text-center text-xs">
          <thead class="border-b border-line text-slate-400">
            <tr>
              <th class="sticky left-0 z-[1] bg-panel px-3 py-2 text-left font-semibold">Employee</th>
              @for (d of sheet()?.dates; track d) { <th class="px-0 py-2 font-medium">{{ d | bdDate: 'd' }}</th> }
              <th class="px-1 py-2 font-semibold">Present</th>
              <th class="px-1 py-2 font-semibold">Late</th>
              <th class="px-1 py-2 font-semibold">Early</th>
              <th class="px-1 py-2 font-semibold">Absent</th>
              <th class="px-1 py-2 font-semibold">Off</th>
              <th class="px-1 py-2 font-semibold">Hours</th>
              <th class="whitespace-nowrap px-3 py-2 text-right font-semibold">Late fees</th>
            </tr>
          </thead>
          <tbody>
            @for (row of sheet()?.rows; track row.userId) {
              <tr class="border-b border-line/60">
                <td class="sticky left-0 z-[1] whitespace-nowrap bg-panel px-3 py-2 text-left text-sm font-medium text-white">{{ row.fullName }}</td>
                @for (d of row.days; track d.date) {
                  <td class="px-px py-1">
                    @if (codes[d.status]) {
                      <span class="block min-w-5 rounded border px-0.5 py-1" [class]="classes[d.status]" [title]="labels[d.status] + (d.lateFee ? ', fee ' + d.lateFee : '')">{{ codes[d.status] }}</span>
                    }
                  </td>
                }
                <td class="px-1 py-2 text-slate-200">{{ row.present }}</td>
                <td class="px-1 py-2 text-slate-200">{{ row.late }}</td>
                <td class="px-1 py-2 text-slate-200">{{ row.early }}</td>
                <td class="px-1 py-2 text-slate-200">{{ row.absent }}</td>
                <td class="px-1 py-2 text-slate-200">{{ row.off }}</td>
                <td class="whitespace-nowrap px-1 py-2 text-slate-200">{{ hours(row.workedMinutes) }}</td>
                <td class="whitespace-nowrap px-3 py-2 text-right font-semibold text-white">{{ row.lateFees | taka }}</td>
              </tr>
            } @empty {
              <tr><td colspan="40" class="px-4 py-10 text-center text-slate-500">No employees yet. Set their working hours in Settings.</td></tr>
            }
          </tbody>
        </table>
      </div>
      @if (sheet(); as s) {
        @if (s.totalElements > 0) {
          <div class="border-t border-line px-4 py-3">
          <app-pagination [page]="s.page" [size]="s.size" [totalElements]="s.totalElements" [totalPages]="s.totalPages" (pageChange)="page.set($event)" />
        </div>
        }
      }
      <p class="p-4 text-xs text-slate-500">P present · L late · E left early · LE both · NC no check-out · A absent · Off day off</p>
    </section>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AttendanceMonthTab {
  private readonly api = inject(AttendanceApi);
  private readonly toast = inject(ToastService);

  protected readonly labels = DAY_STATUS_LABELS;
  protected readonly classes = DAY_STATUS_CLASSES;
  protected readonly codes = DAY_STATUS_CODES;
  protected readonly hours = hoursLabel;
  readonly month = input.required<string>();
  /** Null shows everyone. */
  readonly userId = input.required<number | null>();
  protected readonly page = linkedSignal({ source: () => [this.month(), this.userId()], computation: () => 0 });
  protected readonly exporting = signal(false);

  private readonly data = rxResource({
    params: () => ({ month: this.month(), userId: this.userId(), page: this.page() }),
    stream: ({ params: p }) => this.api.month(p.month, p.userId, p.page, PAGE_SIZE),
  });
  protected readonly sheet = computed(() => (this.data.hasValue() ? this.data.value() : undefined));

  protected exportExcel(): void {
    this.exporting.set(true);
    this.api.exportMonth(this.month(), this.userId()).subscribe({
      next: (response) => {
        saveBlob(response.body!, fileNameFrom(response, `attendance-${this.month()}.xlsx`));
        this.exporting.set(false);
      },
      error: (err: unknown) => {
        void readBlobError(err).then((message) => this.toast.error(message));
        this.exporting.set(false);
      },
    });
  }
}
