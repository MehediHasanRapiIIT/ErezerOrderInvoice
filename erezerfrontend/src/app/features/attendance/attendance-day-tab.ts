import { ChangeDetectionStrategy, Component, computed, inject, input, linkedSignal, output, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../core/auth/auth.service';
import { errorMessage } from '../../core/http/api-error';
import { ToastService } from '../../core/notify/toast.service';
import { isoDate } from '../../shared/date-range';
import { BdDatePipe, bdToday } from '../../shared/ui/bd-date.pipe';
import { Pagination } from '../../shared/ui/pagination';
import { TakaPipe } from '../../shared/ui/taka.pipe';
import { AttendanceReviewDialog } from './review-dialog';
import { AttendanceApi } from './data/attendance.api';
import { AttendanceRecord, DAY_STATUS_CLASSES, DAY_STATUS_LABELS, DayRow, hoursLabel, time12 } from './data/attendance.models';

const PAGE_SIZE = 20;

/** Everyone on one day with their check-in photo, or one employee's month day by day. */
@Component({
  selector: 'app-attendance-day-tab',
  imports: [AttendanceReviewDialog, BdDatePipe, FormsModule, Pagination, TakaPipe],
  template: `
    <section class="card overflow-hidden">
      <div class="flex flex-wrap items-center gap-3 border-b border-line p-4">
        @if (userId() === null) {
          <input type="date" class="field w-auto" [min]="firstDay()" [max]="lastDay()" [ngModel]="date()" (ngModelChange)="setDate($event)" aria-label="Date" />
        } @else {
          <h2 class="font-semibold text-white">{{ rows()[0]?.fullName }}: day by day</h2>
        }
      </div>
      <div class="overflow-x-auto">
        <table class="phone-cards w-full min-w-[920px] text-left text-sm">
          <thead class="border-b border-line text-slate-400">
            <tr>
              <th class="px-4 py-3 font-semibold">{{ userId() === null ? 'Employee' : 'Date' }}</th>
              <th class="px-4 py-3 font-semibold">Shift</th>
              <th class="px-4 py-3 font-semibold">Check in</th>
              <th class="px-4 py-3 font-semibold">Check out</th>
              <th class="px-4 py-3 font-semibold">Hours</th>
              <th class="px-4 py-3 font-semibold">Status</th>
              <th class="px-4 py-3 font-semibold">Reason</th>
              <th class="px-4 py-3"><span class="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            @for (row of rows(); track row.userId + row.date) {
              <tr class="border-b border-line/60">
                <td class="px-4 py-3">
                  <div class="flex items-center gap-3">
                    @if (row.record?.photoUrl; as url) {
                      <a [href]="url" target="_blank" rel="noopener" title="Open the photo" class="shrink-0">
                        <img [src]="url" alt="Check-in photo of {{ row.fullName }}" class="h-12 w-12 rounded-lg object-cover" loading="lazy" />
                      </a>
                    } @else {
                      <span class="flex h-12 w-12 shrink-0 items-center justify-center rounded-lg bg-white/5 text-slate-600"><i class="fas fa-user"></i></span>
                    }
                    <span class="font-medium text-white">{{ userId() === null ? row.fullName : (row.date | bdDate: 'EEE, d MMM') }}</span>
                  </div>
                </td>
                <td class="cell-inline whitespace-nowrap px-4 py-3 text-slate-300" data-label="Shift">{{ time(row.shiftStart) }} – {{ time(row.shiftEnd) }}</td>
                <td class="cell-inline px-4 py-3 text-slate-200" data-label="Check in">
                  @if (row.record?.checkInAt) {
                    <span class="whitespace-nowrap">{{ row.record!.checkInAt | bdDate: 'h:mm a' }}</span>
                    <span class="block text-xs" [class]="placeClass(row.record!, 'in')">{{ place(row.record!, 'in') }}</span>
                  } @else { – }
                </td>
                <td class="cell-inline px-4 py-3 text-slate-200" data-label="Check out">
                  @if (row.record?.checkOutAt) {
                    <span class="whitespace-nowrap">{{ row.record!.checkOutAt | bdDate: 'h:mm a' }}</span>
                    <span class="block text-xs" [class]="placeClass(row.record!, 'out')">{{ place(row.record!, 'out') }}</span>
                  } @else { – }
                </td>
                <td class="cell-inline whitespace-nowrap px-4 py-3 text-slate-200" data-label="Hours">{{ row.record?.workedMinutes != null ? hours(row.record!.workedMinutes!) : '–' }}</td>
                <td class="cell-inline px-4 py-3" data-label="Status">
                  <span class="whitespace-nowrap rounded-full border px-2 py-0.5 text-xs" [class]="statusClasses[row.status]">{{ statusLabels[row.status] }}</span>
                  @if (row.record?.reviewStatus === 'PENDING') { <span class="mt-1 block text-xs text-amber-300">Waiting for approval</span> }
                  @if (row.record?.lateFee) { <span class="mt-1 block text-xs text-rose-300">Fee {{ row.record!.lateFee | taka }}</span> }
                </td>
                <td class="cell-inline px-4 py-3 text-xs text-slate-300" data-label="Reason">
                  {{ row.record?.checkInReason }} {{ row.record?.checkOutReason }}
                  @if (row.record?.editNote) { <span class="block text-slate-500">Corrected: {{ row.record!.editNote }}</span> }
                </td>
                <td class="cell-actions px-4 py-3 text-right">
                  <div class="flex justify-end gap-2">
                    @if (canApprove && row.record && row.record.reviewStatus !== 'NONE') {
                      <button type="button" class="btn-secondary" (click)="reviewing.set(row.record)">{{ row.record.reviewStatus === 'PENDING' ? 'Review' : 'Fee' }}</button>
                    }
                    @if (canEdit) {
                      <button type="button" class="btn-secondary" (click)="openCorrection(row)">Correct</button>
                    }
                  </div>
                </td>
              </tr>
            } @empty {
              <tr><td colspan="8" class="px-4 py-10 text-center text-slate-500">
                @if (day.isLoading()) { Loading… } @else if (userId() !== null) { No attendance in this month. } @else { No employees yet. Set their working hours in Settings. }
              </td></tr>
            }
          </tbody>
        </table>
      </div>
      @if (result(); as r) {
        @if (r.totalElements > 0) {
          <div class="border-t border-line px-4 py-3">
          <app-pagination [page]="r.page" [size]="r.size" [totalElements]="r.totalElements" [totalPages]="r.totalPages" (pageChange)="page.set($event)" />
        </div>
        }
      }
    </section>

    @if (reviewing(); as r) {
      <app-attendance-review-dialog [record]="r" (closed)="reviewClosed($event)" />
    }

    @if (correcting(); as row) {
      <div class="fixed inset-0 z-[85] flex items-center justify-center bg-black/60 p-4">
        <form class="card w-full max-w-sm p-5" (ngSubmit)="saveCorrection(row)">
          <h2 class="text-lg font-semibold text-white">Correct {{ row.fullName }}</h2>
          <p class="text-sm text-slate-400">{{ row.date | bdDate: 'd MMM y' }}. Leave both times empty to mark absent.</p>
          <div class="mt-3 grid grid-cols-2 gap-3">
            <div><label class="field-label" for="fix-in">Check in</label><input id="fix-in" name="in" type="time" class="field" [(ngModel)]="fixIn" /></div>
            <div><label class="field-label" for="fix-out">Check out</label><input id="fix-out" name="out" type="time" class="field" [(ngModel)]="fixOut" /></div>
          </div>
          <label class="field-label mt-3 block" for="fix-note">Why is it being changed?</label>
          <input id="fix-note" name="note" class="field" maxlength="500" required [(ngModel)]="fixNote" />
          <div class="mt-5 flex justify-end gap-2">
            <button type="button" class="btn-secondary" (click)="correcting.set(null)">Cancel</button>
            <button type="submit" class="btn-primary" [disabled]="!fixNote.trim() || saving()">Save</button>
          </div>
        </form>
      </div>
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AttendanceDayTab {
  private readonly api = inject(AttendanceApi);
  private readonly toast = inject(ToastService);
  private readonly auth = inject(AuthService);

  readonly month = input.required<string>();
  /** Null shows everyone on one day; an id shows that employee's month. */
  readonly userId = input.required<number | null>();
  /** Fees or hours may have changed. */
  readonly changed = output<void>();

  protected readonly statusLabels = DAY_STATUS_LABELS;
  protected readonly statusClasses = DAY_STATUS_CLASSES;
  protected readonly time = time12;
  protected readonly hours = hoursLabel;
  protected readonly canApprove = this.auth.hasPermission('ATTENDANCE_APPROVE');
  protected readonly canEdit = this.auth.hasPermission('ATTENDANCE_EDIT');
  /** Today in the current month, otherwise the 1st. */
  protected readonly date = linkedSignal(() => {
    const today = isoDate(bdToday());
    return today.startsWith(this.month()) ? today : `${this.month()}-01`;
  });
  protected readonly firstDay = computed(() => `${this.month()}-01`);
  protected readonly lastDay = computed(() => {
    const [year, month] = this.month().split('-').map(Number);
    return `${this.month()}-${String(new Date(year, month, 0).getDate()).padStart(2, '0')}`;
  });
  protected readonly page = linkedSignal({ source: () => [this.date(), this.month(), this.userId()], computation: () => 0 });
  protected readonly reviewing = signal<AttendanceRecord | null>(null);
  protected readonly correcting = signal<DayRow | null>(null);
  protected readonly saving = signal(false);
  protected fixIn = '';
  protected fixOut = '';
  protected fixNote = '';

  protected readonly day = rxResource({
    params: () => ({ date: this.date(), month: this.month(), userId: this.userId(), page: this.page() }),
    stream: ({ params: p }) =>
      p.userId === null
        ? this.api.day(p.date, p.page, PAGE_SIZE)
        : this.api.employeeDays(p.userId, p.month, p.page, PAGE_SIZE),
  });
  protected readonly result = computed(() => (this.day.hasValue() ? this.day.value() : undefined));
  protected readonly rows = computed(() => this.result()?.content ?? []);

  protected setDate(value: string): void {
    if (value) this.date.set(value);
  }

  /** Where someone checked in or out. A day entered by hand has no position to report. */
  protected place(r: AttendanceRecord, when: 'in' | 'out'): string {
    const [name, inRange, latitude, distance] =
      when === 'in'
        ? [r.checkInLocation, r.checkInInRange, r.checkInLatitude, r.checkInDistanceM]
        : [r.checkOutLocation, r.checkOutInRange, r.checkOutLatitude, r.checkOutDistanceM];
    if (inRange) return name ?? '';
    if (latitude === undefined) return r.editNote ? '' : 'No location';
    return distance === undefined ? 'Away' : `${distance} m from ${name}`;
  }

  protected placeClass(r: AttendanceRecord, when: 'in' | 'out'): string {
    return (when === 'in' ? r.checkInInRange : r.checkOutInRange) ? 'text-slate-500' : 'text-rose-300';
  }

  protected reviewClosed(saved: boolean): void {
    this.reviewing.set(null);
    if (saved) {
      this.day.reload();
      this.changed.emit();
    }
  }

  protected openCorrection(row: DayRow): void {
    this.fixIn = hhmm(row.record?.checkInAt);
    this.fixOut = hhmm(row.record?.checkOutAt);
    this.fixNote = '';
    this.correcting.set(row);
  }

  protected saveCorrection(row: DayRow): void {
    this.saving.set(true);
    this.api
      .correct({ userId: row.userId, date: row.date, checkIn: this.fixIn || null, checkOut: this.fixOut || null, note: this.fixNote.trim() })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.correcting.set(null);
          this.toast.success('Attendance corrected');
          this.day.reload();
          this.changed.emit();
        },
        error: (err: unknown) => {
          this.saving.set(false);
          this.toast.error(errorMessage(err));
        },
      });
  }
}

/** An instant as "HH:mm" in Bangladesh time, for a time box. */
function hhmm(instant: string | undefined): string {
  return instant ? new Date(new Date(instant).getTime() + 6 * 3_600_000).toISOString().slice(11, 16) : '';
}
