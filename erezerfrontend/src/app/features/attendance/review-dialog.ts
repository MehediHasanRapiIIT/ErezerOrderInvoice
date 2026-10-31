import { ChangeDetectionStrategy, Component, inject, input, OnInit, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { errorMessage } from '../../core/http/api-error';
import { ToastService } from '../../core/notify/toast.service';
import { BdDatePipe } from '../../shared/ui/bd-date.pipe';
import { AttendanceApi } from './data/attendance.api';
import { AttendanceRecord, mapLink } from './data/attendance.models';

/** Shows why someone was late, early or away, and approves it, with a late fee if the admin chooses. */
@Component({
  selector: 'app-attendance-review-dialog',
  imports: [BdDatePipe, FormsModule],
  template: `
    <div class="fixed inset-0 z-[85] flex items-start justify-center overflow-y-auto bg-black/60 p-4 sm:items-center">
      <form class="card w-full max-w-md p-5" (ngSubmit)="save()">
        <h2 class="text-lg font-semibold text-white">{{ record().fullName }}</h2>
        <p class="text-sm text-slate-400">{{ record().date | bdDate: 'EEEE, d MMM y' }}</p>
        <div class="mt-3 flex gap-4">
          @if (record().photoUrl) {
            <a [href]="record().photoUrl" target="_blank" rel="noopener" class="shrink-0">
              <img [src]="record().photoUrl" alt="Check-in photo" class="h-24 w-24 rounded-lg object-cover" />
            </a>
          }
          <div class="min-w-0 space-y-1 text-sm text-slate-200">
            @if (record().lateMinutes) { <p class="text-amber-300">{{ record().lateMinutes }} minutes late</p> }
            @if (record().earlyMinutes) { <p class="text-amber-300">Left {{ record().earlyMinutes }} minutes early</p> }
            @if (record().checkInAt && !record().checkInInRange) {
              <p class="text-rose-300">Checked in away from work{{ record().checkInDistanceM != null ? ' (' + record().checkInDistanceM + ' m from ' + record().checkInLocation + ')' : '' }}</p>
            }
            @if (record().checkOutAt && !record().checkOutInRange) {
              <p class="text-rose-300">Checked out away from work{{ record().checkOutDistanceM != null ? ' (' + record().checkOutDistanceM + ' m from ' + record().checkOutLocation + ')' : '' }}</p>
            }
            @if (map()) { <a class="text-brand-light underline" [href]="map()" target="_blank" rel="noopener">Open on map</a> }
          </div>
        </div>
        @if (record().checkInReason) { <p class="mt-3 text-sm text-slate-200"><span class="text-slate-400">Check-in reason:</span> {{ record().checkInReason }}</p> }
        @if (record().checkOutReason) { <p class="mt-2 text-sm text-slate-200"><span class="text-slate-400">Check-out reason:</span> {{ record().checkOutReason }}</p> }

        <label class="field-label mt-4 block" for="late-fee">Late fee (leave 0 for none)</label>
        <input id="late-fee" name="lateFee" type="number" min="0" step="1" class="field" [(ngModel)]="fee" />
        <label class="field-label mt-3 block" for="review-note">Note (optional)</label>
        <input id="review-note" name="note" class="field" maxlength="500" [(ngModel)]="note" />
        <div class="mt-5 flex justify-end gap-2">
          <button type="button" class="btn-secondary" (click)="closed.emit(false)">Cancel</button>
          <button type="submit" class="btn-primary" [disabled]="saving()">{{ (fee || 0) > 0 ? 'Add fee & approve' : 'Approve' }}</button>
        </div>
      </form>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AttendanceReviewDialog implements OnInit {
  private readonly api = inject(AttendanceApi);
  private readonly toast = inject(ToastService);

  readonly record = input.required<AttendanceRecord>();
  /** True when something was saved. */
  readonly closed = output<boolean>();

  protected fee: number | null = 0;
  protected note = '';
  protected readonly saving = signal(false);

  ngOnInit(): void {
    this.fee = this.record().lateFee;
    this.note = this.record().reviewNote ?? '';
  }

  protected map(): string | null {
    const r = this.record();
    return mapLink(r.checkInLatitude, r.checkInLongitude) ?? mapLink(r.checkOutLatitude, r.checkOutLongitude);
  }

  protected save(): void {
    this.saving.set(true);
    this.api.review(this.record().id, this.fee || 0, this.note.trim() || null).subscribe({
      next: () => {
        this.toast.success('Approved');
        this.closed.emit(true);
      },
      error: (err: unknown) => {
        this.toast.error(errorMessage(err));
        this.saving.set(false);
      },
    });
  }
}
