import { ChangeDetectionStrategy, Component, computed, inject, input, linkedSignal, output, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { BdDatePipe } from '../../shared/ui/bd-date.pipe';
import { Pagination } from '../../shared/ui/pagination';
import { AttendanceReviewDialog } from './review-dialog';
import { AttendanceApi } from './data/attendance.api';
import { AttendanceRecord } from './data/attendance.models';

const PAGE_SIZE = 20;

/** Days waiting for the admin: late, left early, or away from every location. */
@Component({
  selector: 'app-attendance-approvals-tab',
  imports: [AttendanceReviewDialog, BdDatePipe, Pagination],
  template: `
    <div class="space-y-3">
      @for (r of rows(); track r.id) {
        <button type="button" class="card flex w-full items-center gap-4 p-4 text-left transition hover:border-brand/60" (click)="reviewing.set(r)">
          @if (r.photoUrl) {
            <img [src]="r.photoUrl" alt="Check-in photo of {{ r.fullName }}" class="h-16 w-16 shrink-0 rounded-lg object-cover" loading="lazy" />
          }
          <span class="min-w-0 flex-1 text-sm">
            <span class="block font-semibold text-white">{{ r.fullName }}</span>
            <span class="block text-slate-400">{{ r.date | bdDate: 'EEE, d MMM y' }}</span>
            <span class="block text-amber-300">
              @if (r.lateMinutes) { {{ r.lateMinutes }} minutes late. }
              @if (r.earlyMinutes) { Left {{ r.earlyMinutes }} minutes early. }
              @if (away(r)) { Away from work. }
            </span>
            <span class="block truncate text-slate-300">{{ r.checkInReason }} {{ r.checkOutReason }}</span>
          </span>
          <i class="fas fa-chevron-right text-slate-500"></i>
        </button>
      } @empty {
        <p class="card p-10 text-center text-slate-500">
          @if (pending.isLoading()) { Loading… } @else { Nothing is waiting for approval. }
        </p>
      }
      @if (result(); as r) {
        @if (r.totalElements > 0) {
          <app-pagination [page]="r.page" [size]="r.size" [totalElements]="r.totalElements" [totalPages]="r.totalPages" (pageChange)="page.set($event)" />
        }
      }
    </div>

    @if (reviewing(); as r) {
      <app-attendance-review-dialog [record]="r" (closed)="closed($event)" />
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AttendanceApprovalsTab {
  private readonly api = inject(AttendanceApi);

  /** Null shows everyone. */
  readonly userId = input.required<number | null>();
  readonly changed = output<void>();
  protected readonly reviewing = signal<AttendanceRecord | null>(null);
  protected readonly page = linkedSignal({ source: this.userId, computation: () => 0 });
  protected readonly pending = rxResource({
    params: () => ({ userId: this.userId(), page: this.page() }),
    stream: ({ params }) => this.api.pending(params.userId, params.page, PAGE_SIZE),
  });
  protected readonly result = computed(() => (this.pending.hasValue() ? this.pending.value() : undefined));
  protected readonly rows = computed(() => this.result()?.content ?? []);

  protected away(r: AttendanceRecord): boolean {
    return (!!r.checkInAt && !r.checkInInRange) || (!!r.checkOutAt && !r.checkOutInRange);
  }

  protected closed(saved: boolean): void {
    this.reviewing.set(null);
    if (saved) {
      // Approving empties the last row of a page; step back rather than show an empty one.
      const r = this.result();
      if (r && r.page > 0 && r.content.length === 1) {
        this.page.set(r.page - 1);
      } else {
        this.pending.reload();
      }
      this.changed.emit();
    }
  }
}
