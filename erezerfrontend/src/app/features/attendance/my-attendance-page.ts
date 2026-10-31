import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { errorMessage, toProblem } from '../../core/http/api-error';
import { ToastService } from '../../core/notify/toast.service';
import { BdDatePipe, bdToday } from '../../shared/ui/bd-date.pipe';
import { TakaPipe } from '../../shared/ui/taka.pipe';
import { isoDate } from '../../shared/date-range';
import { AttendanceApi } from './data/attendance.api';
import { currentPosition, DAY_STATUS_CLASSES, DAY_STATUS_CODES, DAY_STATUS_LABELS, Position, time12 } from './data/attendance.models';
import { SelfieCamera } from './selfie-camera';

/** A check-in or check-out waiting for the reason the server asked for. */
interface PendingMark {
  kind: 'in' | 'out';
  position: Position | null;
  photoFileId: number;
  message: string;
}

/** The employee's own page: check in with a selfie and location, check out, and see the month. */
@Component({
  selector: 'app-my-attendance-page',
  imports: [BdDatePipe, FormsModule, SelfieCamera, TakaPipe],
  template: `
    <h1 class="page-title">My Attendance</h1>
    <p class="page-subtitle">{{ today() | bdDate: 'EEEE, d MMMM y' }}</p>

    <section class="card mt-6 p-5">
      @if (data.isLoading() && !info()) {
        <p class="text-slate-400"><i class="fas fa-circle-notch fa-spin"></i> Loading…</p>
      } @else if (data.error()) {
        <p class="text-rose-300">Couldn't load your attendance.</p>
      } @else if (!shift()) {
        <p class="text-slate-200"><i class="fas fa-info-circle mr-1 text-sky-300"></i>Your working hours haven't been set yet. Ask an admin.</p>
      } @else {
        <p class="text-sm text-slate-400">
          Your hours today: <span class="text-slate-200">{{ time(info()!.todayCheckIn) }} – {{ time(info()!.todayCheckOut) }}</span>
          @if (shift()!.graceMinutes) { <span> ({{ shift()!.graceMinutes }} minutes grace)</span> }
        </p>
        @if (info()!.weeklyOff) {
          <p class="mt-2 text-sm text-sky-300"><i class="fas fa-umbrella-beach mr-1"></i>Today is your day off.</p>
        }

        @if (record(); as r) {
          <div class="mt-4 flex items-center gap-4">
            @if (r.photoUrl) { <img [src]="r.photoUrl" alt="Your check-in photo" class="h-20 w-20 rounded-lg object-cover" /> }
            <div class="min-w-0 text-sm">
              <p class="text-slate-200"><i class="fas fa-sign-in-alt mr-1 text-emerald-300"></i>In at <b>{{ r.checkInAt | bdDate: 'h:mm a' }}</b>{{ r.checkInLocation ? ' · ' + r.checkInLocation : '' }}</p>
              @if (r.checkOutAt) {
                <p class="mt-1 text-slate-200"><i class="fas fa-sign-out-alt mr-1 text-sky-300"></i>Out at <b>{{ r.checkOutAt | bdDate: 'h:mm a' }}</b>{{ r.checkOutLocation ? ' · ' + r.checkOutLocation : '' }}</p>
              }
              @if (r.lateMinutes) { <p class="mt-1 text-amber-300">{{ r.lateMinutes }} minutes late</p> }
              @if (r.earlyMinutes) { <p class="mt-1 text-amber-300">Left {{ r.earlyMinutes }} minutes early</p> }
              @if (r.reviewStatus === 'PENDING') { <p class="mt-1 text-slate-400">Waiting for the admin's approval</p> }
              @if (r.reviewStatus === 'APPROVED') {
                <p class="mt-1 text-emerald-300">Approved</p>
              }
              @if (r.lateFee > 0) { <p class="mt-1 text-rose-300">Late fee: {{ r.lateFee | taka }}</p> }
            </div>
          </div>
        }

        @if (!record()) {
          <button type="button" class="btn-primary mt-5 w-full justify-center py-4 text-lg" [disabled]="busy()" (click)="showCamera.set(true)">
            <i class="fas fa-camera"></i> Check In
          </button>
        } @else if (!record()!.checkOutAt) {
          <button type="button" class="btn-primary mt-5 w-full justify-center py-4 text-lg" [disabled]="busy()" (click)="checkOut()">
            <i class="fas fa-sign-out-alt"></i> Check Out
          </button>
        } @else {
          <p class="mt-4 text-sm text-emerald-300"><i class="fas fa-check-circle mr-1"></i>You're done for today.</p>
        }
        @if (busy()) { <p class="mt-3 text-center text-sm text-slate-400"><i class="fas fa-circle-notch fa-spin"></i> {{ busy() }}</p> }
      }
    </section>

    <section class="card mt-6 p-5">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <h2 class="text-lg font-semibold text-white">This month</h2>
        <input type="month" class="field w-auto" [ngModel]="month()" (ngModelChange)="setMonth($event)" aria-label="Month" />
      </div>
      @if (summary(); as m) {
        <div class="mt-4 grid grid-cols-2 gap-3 text-sm sm:grid-cols-5">
          <p class="text-slate-400">Present <b class="block text-lg text-white">{{ m.present }}</b></p>
          <p class="text-slate-400">Late <b class="block text-lg text-white">{{ m.late }}</b></p>
          <p class="text-slate-400">Left early <b class="block text-lg text-white">{{ m.early }}</b></p>
          <p class="text-slate-400">Absent <b class="block text-lg text-white">{{ m.absent }}</b></p>
          <p class="text-slate-400">Late fees <b class="block text-lg text-white">{{ m.lateFees | taka }}</b></p>
        </div>
        <div class="mt-4 grid grid-cols-7 gap-1 text-center text-xs">
          @for (d of weekHeads; track d) { <span class="py-1 text-slate-500">{{ d }}</span> }
          @for (b of blanks(); track $index) { <span></span> }
          @for (d of m.days; track d.date) {
            <span class="rounded border py-1" [class]="statusClasses[d.status]" [title]="statusLabels[d.status]">
              <span class="block font-semibold">{{ d.date | bdDate: 'd' }}</span>
              <span class="block h-4">{{ statusCodes[d.status] }}</span>
            </span>
          }
        </div>
        <p class="mt-3 text-xs text-slate-500">P present · L late · E left early · LE both · NC no check-out · A absent</p>
      }
    </section>

    @if (showCamera()) {
      <app-selfie-camera (captured)="photoTaken($event)" (cancelled)="showCamera.set(false)" />
    }

    @if (pending(); as p) {
      <div class="fixed inset-0 z-[85] flex items-center justify-center bg-black/60 p-4">
        <form class="card w-full max-w-md p-5" (ngSubmit)="sendReason()">
          <h2 class="text-lg font-semibold text-white">Give a reason</h2>
          <p class="mt-2 text-sm text-amber-300">{{ p.message }}</p>
          <textarea class="field mt-3" rows="3" maxlength="500" name="reason" [(ngModel)]="reason" aria-label="Reason" required></textarea>
          <p class="mt-2 text-xs text-slate-500">The admin will see this and approve it.</p>
          <div class="mt-4 flex justify-end gap-2">
            <button type="button" class="btn-secondary" (click)="pending.set(null)">Cancel</button>
            <button type="submit" class="btn-primary" [disabled]="!reason.trim() || !!busy()">Send</button>
          </div>
        </form>
      </div>
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MyAttendancePage {
  private readonly api = inject(AttendanceApi);
  private readonly toast = inject(ToastService);

  protected readonly statusLabels = DAY_STATUS_LABELS;
  protected readonly statusClasses = DAY_STATUS_CLASSES;
  protected readonly statusCodes = DAY_STATUS_CODES;
  protected readonly weekHeads = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  protected readonly time = time12;
  protected readonly today = signal(isoDate(bdToday()));
  protected readonly month = signal(this.today().slice(0, 7));
  protected readonly showCamera = signal(false);
  protected readonly busy = signal('');
  protected readonly pending = signal<PendingMark | null>(null);
  protected reason = '';

  protected readonly data = rxResource({ stream: () => this.api.today() });
  private readonly monthData = rxResource({ params: () => this.month(), stream: ({ params }) => this.api.myMonth(params) });

  protected readonly info = computed(() => (this.data.hasValue() ? this.data.value() : undefined));
  protected readonly shift = computed(() => this.info()?.shift);
  protected readonly record = computed(() => this.info()?.record);
  protected readonly summary = computed(() => (this.monthData.hasValue() ? this.monthData.value() : undefined));
  /** Empty squares before the 1st, so days line up under their weekday. */
  protected readonly blanks = computed(() => {
    const first = this.summary()?.days[0]?.date;
    return first ? Array(new Date(`${first}T00:00:00Z`).getUTCDay()) : [];
  });

  protected setMonth(value: string): void {
    if (value) this.month.set(value);
  }

  protected async photoTaken(photo: Blob): Promise<void> {
    this.showCamera.set(false);
    try {
      this.busy.set('Saving your photo…');
      const file = await firstValueFrom(this.api.uploadSelfie(photo));
      this.busy.set('Finding your location…');
      const position = await currentPosition();
      await this.mark({ kind: 'in', position, photoFileId: file.id, message: '' }, null);
    } catch (err) {
      this.toast.error(errorMessage(err));
    } finally {
      this.busy.set('');
    }
  }

  protected async checkOut(): Promise<void> {
    try {
      this.busy.set('Finding your location…');
      const position = await currentPosition();
      await this.mark({ kind: 'out', position, photoFileId: 0, message: '' }, null);
    } finally {
      this.busy.set('');
    }
  }

  protected async sendReason(): Promise<void> {
    const pending = this.pending();
    if (!pending || !this.reason.trim()) return;
    try {
      this.busy.set('Saving…');
      await this.mark(pending, this.reason.trim());
    } finally {
      this.busy.set('');
    }
  }

  private async mark(mark: PendingMark, reason: string | null): Promise<void> {
    this.busy.set('Saving…');
    try {
      await firstValueFrom(
        mark.kind === 'in'
          ? this.api.checkIn(mark.position, mark.photoFileId, reason)
          : this.api.checkOut(mark.position, reason),
      );
      this.pending.set(null);
      this.reason = '';
      this.toast.success(mark.kind === 'in' ? 'Checked in' : 'Checked out');
      this.data.reload();
      this.monthData.reload();
    } catch (err) {
      const problem = toProblem(err);
      if (problem.code === 'REASON_REQUIRED') {
        this.pending.set({ ...mark, message: problem.detail ?? 'Please give a reason.' });
      } else {
        this.toast.error(errorMessage(err));
      }
    }
  }
}
