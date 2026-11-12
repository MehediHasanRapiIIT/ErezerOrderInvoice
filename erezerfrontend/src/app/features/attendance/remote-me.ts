import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { errorMessage, toProblem } from '../../core/http/api-error';
import { ToastService } from '../../core/notify/toast.service';
import { isoDate } from '../../shared/date-range';
import { BdDatePipe, bdToday } from '../../shared/ui/bd-date.pipe';
import { TakaPipe } from '../../shared/ui/taka.pipe';
import { hoursLabel, time12 } from './data/attendance.models';
import { RemoteApi } from './data/remote.api';
import { REMOTE_STATUS_CLASSES, REMOTE_STATUS_CODES, REMOTE_STATUS_LABELS } from './data/remote.models';

/** What is waiting for the reason the server asked for. */
interface Pending {
  kind: 'on' | 'done';
  message: string;
}

/** A remote worker's own page: Reply On and Reply Off, today's sessions and the month. */
@Component({
  selector: 'app-remote-me',
  imports: [BdDatePipe, FormsModule, TakaPipe],
  template: `
    <section class="card mt-6 p-5">
      @if (data.isLoading() && !me()) {
        <p class="text-slate-400"><i class="fas fa-circle-notch fa-spin"></i> Loading…</p>
      } @else if (data.error()) {
        <p class="text-rose-300">Couldn't load your attendance.</p>
      } @else if (me(); as m) {
        <p class="text-sm text-slate-400">
          <i class="fas fa-house-laptop mr-1 text-sky-300"></i>You work remotely.
          @if (m.start && m.end) {
            Your hours today: <span class="text-slate-200">{{ time(m.start) }} – {{ time(m.end) }}</span>
          } @else if (!m.dayOff) {
            No fixed hours.
          }
        </p>
        @if (m.dayOff) { <p class="mt-2 text-sm text-sky-300"><i class="fas fa-umbrella-beach mr-1"></i>Today is your day off.</p> }

        <div class="mt-5 flex flex-col items-center gap-2 text-center">
          <span class="inline-flex items-center gap-2 rounded-full border px-3 py-1 text-sm"
            [class]="m.on ? 'border-emerald-500/60 bg-emerald-500/15 text-emerald-200' : 'border-line bg-white/5 text-slate-400'">
            <span class="h-2 w-2 rounded-full" [class]="m.on ? 'bg-emerald-400' : 'bg-slate-500'"></span>
            {{ m.on ? 'Reply On since ' + (m.onSince | bdDate: 'h:mm a') : 'Reply Off' }}
          </span>
          <p class="text-3xl font-bold text-white">{{ hours(worked()) }}</p>
          <p class="text-xs text-slate-500">worked today (breaks don't count)</p>
        </div>

        @if (!m.on) {
          <div class="mt-5 grid gap-3">
            <label class="field-label" for="remote-note">Note (optional)</label>
            <input id="remote-note" class="field" maxlength="300" [(ngModel)]="note" placeholder="What are you starting on?" />
            <div class="flex flex-wrap items-center gap-3">
              <label class="btn-secondary cursor-pointer">
                <i class="fas fa-image"></i> {{ image() ? 'Change image' : 'Add image (optional)' }}
                <input type="file" accept="image/*" class="hidden" (change)="pickImage($event)" />
              </label>
              @if (image(); as f) {
                <span class="min-w-0 truncate text-sm text-slate-300">{{ f.name }}</span>
                <button type="button" class="text-sm text-slate-400 hover:text-white" (click)="image.set(null)" aria-label="Remove image"><i class="fas fa-times"></i></button>
              }
            </div>
            <button type="button" class="btn-primary w-full justify-center py-4 text-lg" [disabled]="!!busy()" (click)="replyOn(null)">
              <i class="fas fa-toggle-on"></i> Reply On
            </button>
          </div>
        } @else {
          <div class="mt-5 grid gap-3 sm:grid-cols-2">
            <button type="button" class="btn-secondary justify-center py-4 text-base" [disabled]="!!busy()" (click)="replyOff(false, null)">
              <i class="fas fa-mug-hot"></i> Reply Off · Break
            </button>
            <button type="button" class="btn-primary justify-center py-4 text-base" [disabled]="!!busy()" (click)="replyOff(true, null)">
              <i class="fas fa-flag-checkered"></i> Reply Off · Done for today
            </button>
          </div>
          <p class="mt-2 text-center text-xs text-slate-500">A break never needs a reason. Finishing before your end time does. Time before your start or after your end counts as extra.</p>
        }
        @if (busy()) { <p class="mt-3 text-center text-sm text-slate-400"><i class="fas fa-circle-notch fa-spin"></i> {{ busy() }}</p> }

        @if (m.day; as d) {
          <div class="mt-5 space-y-1 text-sm">
            @if (d.lateMinutes) { <p class="text-amber-300">{{ d.lateMinutes }} minutes late{{ d.lateReason ? ': ' + d.lateReason : '' }}</p> }
            @if (d.earlyMinutes) { <p class="text-amber-300">Finished {{ d.earlyMinutes }} minutes early{{ d.earlyReason ? ': ' + d.earlyReason : '' }}</p> }
            @if (d.extraMinutes) { <p class="text-emerald-300">{{ hours(d.extraMinutes) }} extra (outside your hours)</p> }
            @if (d.notTurnedOff) { <p class="text-sky-300">You forgot Reply Off; the system closed it.</p> }
            @if (d.reviewStatus === 'PENDING') { <p class="text-slate-400">Waiting for the admin's approval</p> }
            @if (d.reviewStatus === 'APPROVED') { <p class="text-emerald-300">Approved</p> }
            @if (d.lateFee > 0) { <p class="text-rose-300">Late fee: {{ d.lateFee | taka }}</p> }
          </div>
          <ul class="mt-4 divide-y divide-line/60 rounded-lg border border-line">
            @for (s of d.sessions; track s.id) {
              <li class="flex items-center gap-3 p-3 text-sm">
                @if (s.imageUrl) {
                  <a [href]="s.imageUrl" target="_blank" rel="noopener"><img [src]="s.imageUrl" alt="Session image" class="h-10 w-10 rounded object-cover" /></a>
                }
                <div class="min-w-0 flex-1">
                  <p class="text-slate-200">{{ s.onAt | bdDate: 'h:mm a' }} – {{ s.offAt ? (s.offAt | bdDate: 'h:mm a') : 'now' }}
                    @if (s.autoClosed) { <span class="ml-1 text-xs text-sky-300">closed by the system</span> }
                  </p>
                  @if (s.note) { <p class="truncate text-xs text-slate-400">{{ s.note }}</p> }
                </div>
                <span class="text-slate-300">{{ hours(s.minutes) }}</span>
              </li>
            }
          </ul>
        }
      }
    </section>

    <section class="card mt-6 p-5">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <h2 class="text-lg font-semibold text-white">This month</h2>
        <input type="month" class="field w-auto" [ngModel]="month()" (ngModelChange)="setMonth($event)" aria-label="Month" />
      </div>
      @if (monthSummary(); as mm) {
        <div class="mt-4 grid grid-cols-2 gap-3 text-sm sm:grid-cols-6">
          <p class="text-slate-400">Hours <b class="block text-lg text-white">{{ hours(mm.summary.workedMinutes) }}</b></p>
          <p class="text-slate-400">Extra time <b class="block text-lg text-emerald-300">{{ hours(mm.summary.extraMinutes) }}</b></p>
          <p class="text-slate-400">Days worked <b class="block text-lg text-white">{{ mm.summary.daysWorked }}</b></p>
          <p class="text-slate-400">Late <b class="block text-lg text-white">{{ mm.summary.lateDays }}</b></p>
          <p class="text-slate-400">Finished early <b class="block text-lg text-white">{{ mm.summary.earlyDays }}</b></p>
          <p class="text-slate-400">Late fees <b class="block text-lg text-white">{{ mm.summary.lateFees | taka }}</b></p>
        </div>
        <div class="mt-4 grid grid-cols-7 gap-1 text-center text-xs">
          @for (d of weekHeads; track d) { <span class="py-1 text-slate-500">{{ d }}</span> }
          @for (b of blanks(); track $index) { <span></span> }
          @for (d of mm.days; track d.date) {
            <span class="rounded border py-1" [class]="statusClasses[d.status]" [title]="statusLabels[d.status] + (d.workedMinutes ? ' · ' + hours(d.workedMinutes) : '')">
              <span class="block font-semibold">{{ d.date | bdDate: 'd' }}</span>
              <span class="block h-4">{{ statusCodes[d.status] }}</span>
            </span>
          }
        </div>
        <p class="mt-3 text-xs text-slate-500">W worked · L late · E finished early · LE both · NO forgot Reply Off · A absent</p>
      }
    </section>

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
export class RemoteMe {
  private readonly api = inject(RemoteApi);
  private readonly toast = inject(ToastService);

  protected readonly statusLabels = REMOTE_STATUS_LABELS;
  protected readonly statusClasses = REMOTE_STATUS_CLASSES;
  protected readonly statusCodes = REMOTE_STATUS_CODES;
  protected readonly weekHeads = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  protected readonly time = time12;
  protected readonly hours = hoursLabel;
  protected readonly month = signal(isoDate(bdToday()).slice(0, 7));
  protected readonly busy = signal('');
  protected readonly pending = signal<Pending | null>(null);
  protected readonly image = signal<File | null>(null);
  /** Ticks every half minute so the time worked keeps counting while On. */
  private readonly now = signal(Date.now());
  protected note = '';
  protected reason = '';

  protected readonly data = rxResource({ stream: () => this.api.me() });
  private readonly monthData = rxResource({ params: () => this.month(), stream: ({ params }) => this.api.myMonth(params) });

  protected readonly me = computed(() => (this.data.hasValue() ? this.data.value() : undefined));
  protected readonly monthSummary = computed(() => (this.monthData.hasValue() ? this.monthData.value() : undefined));
  /** Minutes worked today, counting the open session up to now. */
  protected readonly worked = computed(() => {
    const m = this.me();
    if (!m) return 0;
    const closed = (m.day?.sessions ?? []).filter((s) => s.offAt).reduce((sum, s) => sum + s.minutes, 0);
    const open = m.onSince ? Math.max(0, Math.floor((this.now() - Date.parse(m.onSince)) / 60_000)) : 0;
    return closed + open;
  });
  protected readonly blanks = computed(() => {
    const first = this.monthSummary()?.days[0]?.date;
    return first ? Array(new Date(`${first}T00:00:00Z`).getUTCDay()) : [];
  });

  constructor() {
    const timer = setInterval(() => this.now.set(Date.now()), 30_000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  protected setMonth(value: string): void {
    if (value) this.month.set(value);
  }

  protected pickImage(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.image.set(input.files?.[0] ?? null);
    input.value = '';
  }

  protected async replyOn(reason: string | null): Promise<void> {
    this.busy.set('Saving…');
    try {
      const file = this.image();
      const imageFileId = file ? (await firstValueFrom(this.api.uploadImage(file))).id : null;
      await firstValueFrom(this.api.on({ imageFileId, note: this.note.trim() || null, reason }));
      this.image.set(null);
      this.note = '';
      this.done('Reply On');
    } catch (err) {
      this.fail(err, 'on');
    } finally {
      this.busy.set('');
    }
  }

  protected async replyOff(done: boolean, reason: string | null): Promise<void> {
    this.busy.set('Saving…');
    try {
      await firstValueFrom(this.api.off({ done, note: null, reason }));
      this.done(done ? 'Done for today' : 'On a break');
    } catch (err) {
      this.fail(err, 'done');
    } finally {
      this.busy.set('');
    }
  }

  protected sendReason(): void {
    const p = this.pending();
    const reason = this.reason.trim();
    if (!p || !reason) return;
    void (p.kind === 'on' ? this.replyOn(reason) : this.replyOff(true, reason));
  }

  private done(message: string): void {
    this.pending.set(null);
    this.reason = '';
    this.toast.success(message);
    this.data.reload();
    this.monthData.reload();
  }

  private fail(err: unknown, kind: Pending['kind']): void {
    const problem = toProblem(err);
    if (problem.code === 'REASON_REQUIRED') {
      this.pending.set({ kind, message: problem.detail ?? 'Please give a reason.' });
    } else {
      this.toast.error(errorMessage(err));
    }
  }
}
