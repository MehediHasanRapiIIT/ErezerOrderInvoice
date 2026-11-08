import { ChangeDetectionStrategy, Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../core/auth/auth.service';
import { errorMessage } from '../../core/http/api-error';
import { fileNameFrom, saveBlob } from '../../core/http/file-download';
import { ToastService } from '../../core/notify/toast.service';
import { toBdInputValue } from '../../shared/bd-datetime-input';
import { isoDate } from '../../shared/date-range';
import { BdDatePipe, bdToday } from '../../shared/ui/bd-date.pipe';
import { ConfirmService } from '../../shared/ui/confirm-dialog';
import { Pagination } from '../../shared/ui/pagination';
import { TakaPipe } from '../../shared/ui/taka.pipe';
import { hoursLabel, time12 } from './data/attendance.models';
import { RemoteApi } from './data/remote.api';
import { REMOTE_STATUS_CLASSES, REMOTE_STATUS_LABELS, RemoteDay, RemoteSession } from './data/remote.models';

type View = 'people' | 'days' | 'approvals';

/** A session being added or edited by hand. */
interface SessionDraft {
  id: number | null;
  userId: number;
  fullName: string;
  date: string;
  on: string;
  off: string;
  note: string;
}

/** Remote attendance for the admin: cards per moderator, each day's sessions, approvals and corrections. */
@Component({
  selector: 'app-remote-tab',
  imports: [BdDatePipe, FormsModule, Pagination, TakaPipe],
  template: `
    <div class="flex flex-wrap gap-3">
      <select class="field w-full sm:w-64" aria-label="Moderator" [ngModel]="userId()" (ngModelChange)="userId.set($event)">
        <option [ngValue]="null">All remote workers</option>
        @for (p of everyone(); track p.userId) {
          <option [ngValue]="p.userId">{{ p.fullName }}</option>
        }
      </select>
      <input type="month" class="field w-full sm:w-auto" [ngModel]="month()" (ngModelChange)="setMonth($event)" aria-label="Month" />
      <button type="button" class="btn-secondary" (click)="exportExcel()" [disabled]="exporting()">
        <i class="fas" [class.fa-file-excel]="!exporting()" [class.text-emerald-400]="!exporting()" [class.fa-circle-notch]="exporting()" [class.fa-spin]="exporting()"></i> Export
      </button>
    </div>

    <div class="mt-4 grid grid-cols-2 gap-3 lg:grid-cols-6">
      <div class="card p-4"><p class="text-xs text-slate-400"><i class="fas fa-clock mr-1 text-sky-300"></i>Hours worked</p><p class="mt-1 text-xl font-bold text-white">{{ hours(summary()?.workedMinutes ?? 0) }}</p></div>
      <div class="card p-4"><p class="text-xs text-slate-400"><i class="fas fa-calendar-check mr-1 text-emerald-300"></i>Days worked</p><p class="mt-1 text-xl font-bold text-white">{{ summary()?.daysWorked ?? 0 }}</p></div>
      <div class="card p-4"><p class="text-xs text-slate-400"><i class="fas fa-hourglass-start mr-1 text-amber-300"></i>Late days</p><p class="mt-1 text-xl font-bold text-white">{{ summary()?.lateDays ?? 0 }}</p></div>
      <div class="card p-4"><p class="text-xs text-slate-400"><i class="fas fa-door-open mr-1 text-amber-300"></i>Finished early</p><p class="mt-1 text-xl font-bold text-white">{{ summary()?.earlyDays ?? 0 }}</p></div>
      <div class="card p-4"><p class="text-xs text-slate-400"><i class="fas fa-power-off mr-1 text-sky-300"></i>Forgot Reply Off</p><p class="mt-1 text-xl font-bold text-white">{{ summary()?.notTurnedOff ?? 0 }}</p></div>
      <div class="card p-4"><p class="text-xs text-slate-400"><i class="fas fa-coins mr-1 text-rose-300"></i>Late fees</p><p class="mt-1 text-xl font-bold text-white">{{ summary()?.lateFees ?? 0 | taka }}</p></div>
    </div>
    <p class="mt-1 text-xs text-slate-500">{{ scope() }} · {{ month() + '-01' | bdDate: 'MMMM y' }}</p>

    <div class="mt-4 flex flex-wrap gap-2">
      @for (v of views; track v.value) {
        <button type="button" class="rounded-full border px-3 py-1 text-sm" [class]="view() === v.value ? 'border-brand bg-brand/20 text-white' : 'border-line text-slate-400 hover:text-white'" (click)="view.set(v.value)">
          {{ v.label }}
        </button>
      }
    </div>

    <div class="mt-4">
      @switch (view()) {
        @case ('people') {
          <div class="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
            @for (p of peoplePage()?.content ?? []; track p.userId) {
              <button type="button" class="card p-4 text-left transition hover:border-brand/60" (click)="openPerson(p.userId)">
                <div class="flex items-center justify-between gap-2">
                  <p class="truncate font-semibold text-white">{{ p.fullName }}</p>
                  <span class="inline-flex shrink-0 items-center gap-1 rounded-full border px-2 py-0.5 text-xs" [class]="p.on ? 'border-emerald-500/60 bg-emerald-500/15 text-emerald-200' : 'border-line text-slate-400'">
                    <span class="h-1.5 w-1.5 rounded-full" [class]="p.on ? 'bg-emerald-400' : 'bg-slate-500'"></span>
                    {{ p.on ? 'On since ' + (p.onSince | bdDate: 'h:mm a') : 'Off' }}
                  </span>
                </div>
                <dl class="mt-3 grid grid-cols-3 gap-2 text-xs text-slate-400">
                  <div><dt>Hours</dt><dd class="text-sm font-semibold text-white">{{ hours(p.workedMinutes) }}</dd></div>
                  <div><dt>Days</dt><dd class="text-sm font-semibold text-white">{{ p.daysWorked }}</dd></div>
                  <div><dt>Late</dt><dd class="text-sm font-semibold text-white">{{ p.lateDays }}</dd></div>
                  <div><dt>Early</dt><dd class="text-sm font-semibold text-white">{{ p.earlyDays }}</dd></div>
                  <div><dt>Forgot Off</dt><dd class="text-sm font-semibold text-white">{{ p.notTurnedOff }}</dd></div>
                  <div><dt>Fees</dt><dd class="text-sm font-semibold text-white">{{ p.lateFees | taka }}</dd></div>
                </dl>
              </button>
            } @empty {
              <p class="card p-10 text-center text-slate-500 sm:col-span-2 xl:col-span-3">
                @if (people.isLoading()) { Loading… } @else { Nobody works remotely yet. Switch someone to Remote in Settings. }
              </p>
            }
          </div>
          @if (peoplePage(); as r) {
            @if (r.totalElements > 0) { <div class="mt-3"><app-pagination [page]="r.page" [size]="r.size" [totalElements]="r.totalElements" [totalPages]="r.totalPages" (pageChange)="peoplePageNo.set($event)" /></div> }
          }
        }
        @case ('days') {
          @if (userId() === null) {
            <input type="date" class="field mb-3 w-full sm:w-auto" [ngModel]="date()" (ngModelChange)="setDate($event)" aria-label="Date" />
          }
          <div class="space-y-3">
            @for (d of daysPage()?.content ?? []; track d.userId + d.date) {
              <div class="card p-4">
                <div class="flex flex-wrap items-center gap-2">
                  <p class="font-semibold text-white">{{ userId() === null ? d.fullName : (d.date | bdDate: 'EEE, d MMM y') }}</p>
                  <span class="rounded-full border px-2 py-0.5 text-xs" [class]="statusClasses[d.status]">{{ statusLabels[d.status] }}</span>
                  @if (d.reviewStatus === 'PENDING') { <span class="rounded-full border border-amber-500/40 px-2 py-0.5 text-xs text-amber-300">Needs approval</span> }
                  @if (d.lateFee > 0) { <span class="text-xs text-rose-300">Fee {{ d.lateFee | taka }}</span> }
                  <span class="ml-auto text-sm text-slate-300">{{ hours(d.workedMinutes) }}</span>
                </div>
                <p class="mt-1 text-xs text-slate-500">Hours: {{ d.shiftStart ? time(d.shiftStart) + ' – ' + time(d.shiftEnd) : 'not fixed' }}</p>
                @if (d.lateMinutes) { <p class="mt-1 text-sm text-amber-300">{{ d.lateMinutes }} min late{{ d.lateReason ? ': ' + d.lateReason : '' }}</p> }
                @if (d.earlyMinutes) { <p class="mt-1 text-sm text-amber-300">Finished {{ d.earlyMinutes }} min early{{ d.earlyReason ? ': ' + d.earlyReason : '' }}</p> }
                @if (d.reviewNote) { <p class="mt-1 text-xs text-slate-400">Admin note: {{ d.reviewNote }}{{ d.reviewedBy ? ' (' + d.reviewedBy + ')' : '' }}</p> }
                @if (d.sessions.length) {
                  <ul class="mt-3 divide-y divide-line/60 rounded-lg border border-line">
                    @for (s of d.sessions; track s.id) {
                      <li class="flex flex-wrap items-center gap-3 p-2 text-sm">
                        @if (s.imageUrl) { <a [href]="s.imageUrl" target="_blank" rel="noopener"><img [src]="s.imageUrl" alt="Session image" class="h-10 w-10 rounded object-cover" loading="lazy" /></a> }
                        <div class="min-w-0 flex-1">
                          <p class="text-slate-200">{{ s.onAt | bdDate: 'h:mm a' }} – {{ s.offAt ? (s.offAt | bdDate: 'h:mm a') : 'now' }}
                            @if (s.autoClosed) { <span class="ml-1 text-xs text-sky-300">closed by the system</span> }
                          </p>
                          @if (s.note) { <p class="truncate text-xs text-slate-400">{{ s.note }}</p> }
                          @if (s.editNote) { <p class="truncate text-xs text-slate-500">Edited: {{ s.editNote }}</p> }
                        </div>
                        <span class="text-slate-300">{{ hours(s.minutes) }}</span>
                        @if (canEdit) {
                          <button type="button" class="btn-secondary px-2 py-1" title="Edit" aria-label="Edit session" (click)="editSession(d, s)"><i class="fas fa-pen"></i></button>
                          <button type="button" class="btn-secondary px-2 py-1 text-red-300" title="Delete" aria-label="Delete session" (click)="deleteSession(s)"><i class="fas fa-trash"></i></button>
                        }
                      </li>
                    }
                  </ul>
                }
                <div class="mt-3 flex flex-wrap gap-2">
                  @if (canApprove && d.id) {
                    <button type="button" class="btn-secondary" (click)="startReview(d)"><i class="fas fa-check"></i> {{ d.reviewStatus === 'PENDING' ? 'Approve' : 'Late fee' }}</button>
                  }
                  @if (canEdit) {
                    <button type="button" class="btn-secondary" (click)="addSession(d)"><i class="fas fa-plus"></i> Add session</button>
                    @if (d.id) { <button type="button" class="btn-secondary text-red-300" (click)="deleteDay(d)"><i class="fas fa-trash"></i> Delete day</button> }
                  }
                </div>
              </div>
            } @empty {
              <p class="card p-10 text-center text-slate-500">@if (days.isLoading()) { Loading… } @else { Nothing recorded. }</p>
            }
          </div>
          @if (daysPage(); as r) {
            @if (r.totalElements > 0) { <div class="mt-3"><app-pagination [page]="r.page" [size]="r.size" [totalElements]="r.totalElements" [totalPages]="r.totalPages" (pageChange)="daysPageNo.set($event)" /></div> }
          }
        }
        @case ('approvals') {
          <div class="space-y-3">
            @for (d of pendingPage()?.content ?? []; track d.id) {
              <button type="button" class="card flex w-full items-center gap-4 p-4 text-left transition hover:border-brand/60" (click)="startReview(d)">
                <span class="min-w-0 flex-1 text-sm">
                  <span class="block font-semibold text-white">{{ d.fullName }}</span>
                  <span class="block text-slate-400">{{ d.date | bdDate: 'EEE, d MMM y' }} · {{ hours(d.workedMinutes) }}</span>
                  <span class="block text-amber-300">
                    @if (d.lateMinutes) { {{ d.lateMinutes }} minutes late. }
                    @if (d.earlyMinutes) { Finished {{ d.earlyMinutes }} minutes early. }
                    @if (d.notTurnedOff) { Forgot Reply Off. }
                  </span>
                  <span class="block truncate text-slate-300">{{ d.lateReason }} {{ d.earlyReason }}</span>
                </span>
                <i class="fas fa-chevron-right text-slate-500"></i>
              </button>
            } @empty {
              <p class="card p-10 text-center text-slate-500">@if (pending.isLoading()) { Loading… } @else { Nothing is waiting for approval. }</p>
            }
          </div>
          @if (pendingPage(); as r) {
            @if (r.totalElements > 0) { <div class="mt-3"><app-pagination [page]="r.page" [size]="r.size" [totalElements]="r.totalElements" [totalPages]="r.totalPages" (pageChange)="pendingPageNo.set($event)" /></div> }
          }
        }
      }
    </div>

    @if (reviewing(); as d) {
      <div class="fixed inset-0 z-[85] flex items-start justify-center overflow-y-auto bg-black/60 p-4 sm:items-center">
        <form class="card w-full max-w-md p-5" (ngSubmit)="saveReview(fee)">
          <h2 class="text-lg font-semibold text-white">{{ d.fullName }}</h2>
          <p class="text-sm text-slate-400">{{ d.date | bdDate: 'EEEE, d MMM y' }} · {{ hours(d.workedMinutes) }} worked</p>
          <div class="mt-3 space-y-1 text-sm">
            @if (d.lateMinutes) { <p class="text-amber-300">{{ d.lateMinutes }} minutes late</p> }
            @if (d.lateReason) { <p class="text-slate-200"><span class="text-slate-400">Reason:</span> {{ d.lateReason }}</p> }
            @if (d.earlyMinutes) { <p class="text-amber-300">Finished {{ d.earlyMinutes }} minutes early</p> }
            @if (d.earlyReason) { <p class="text-slate-200"><span class="text-slate-400">Reason:</span> {{ d.earlyReason }}</p> }
            @if (d.notTurnedOff) { <p class="text-sky-300">Forgot Reply Off; the system closed it.</p> }
          </div>
          <label class="field-label mt-4 block" for="remote-fee">Late fee (leave 0 for none)</label>
          <input id="remote-fee" name="lateFee" type="number" min="0" step="1" class="field" [(ngModel)]="fee" />
          <label class="field-label mt-3 block" for="remote-review-note">Note (optional)</label>
          <input id="remote-review-note" name="note" class="field" maxlength="500" [(ngModel)]="reviewNote" />
          <div class="mt-5 flex flex-wrap justify-end gap-2">
            <button type="button" class="btn-secondary" (click)="reviewing.set(null)">Cancel</button>
            @if (d.lateFee > 0) { <button type="button" class="btn-secondary text-red-300" [disabled]="saving()" (click)="saveReview(0)">Remove fee</button> }
            <button type="submit" class="btn-primary" [disabled]="saving()">{{ d.reviewStatus !== 'PENDING' ? 'Save fee' : (fee || 0) > 0 ? 'Add fee & approve' : 'Approve' }}</button>
          </div>
        </form>
      </div>
    }

    @if (draft(); as s) {
      <div class="fixed inset-0 z-[85] flex items-start justify-center overflow-y-auto bg-black/60 p-4 sm:items-center">
        <form class="card w-full max-w-md p-5" (ngSubmit)="saveSession(s)">
          <h2 class="text-lg font-semibold text-white">{{ s.id ? 'Edit session' : 'Add session' }}</h2>
          <p class="text-sm text-slate-400">{{ s.fullName }} · {{ s.date | bdDate: 'EEE, d MMM y' }}</p>
          <div class="mt-4 grid grid-cols-2 gap-3">
            <div><label class="field-label" for="session-on">Reply On</label><input id="session-on" name="on" type="time" class="field" [(ngModel)]="s.on" required /></div>
            <div><label class="field-label" for="session-off">Reply Off</label><input id="session-off" name="off" type="time" class="field" [(ngModel)]="s.off" /></div>
          </div>
          <p class="mt-1 text-xs text-slate-500">Leave Reply Off empty only for a session still running today.</p>
          <label class="field-label mt-3 block" for="session-note">Why (required)</label>
          <input id="session-note" name="note" class="field" maxlength="300" [(ngModel)]="s.note" required />
          <div class="mt-5 flex justify-end gap-2">
            <button type="button" class="btn-secondary" (click)="draft.set(null)">Cancel</button>
            <button type="submit" class="btn-primary" [disabled]="saving() || !s.on || !s.note.trim()">Save</button>
          </div>
        </form>
      </div>
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RemoteTab {
  private readonly api = inject(RemoteApi);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);

  protected readonly canApprove = this.auth.hasPermission('REMOTE_APPROVE');
  protected readonly canEdit = this.auth.hasPermission('REMOTE_EDIT');
  protected readonly views: readonly { value: View; label: string }[] = [
    { value: 'people', label: 'Moderators' },
    { value: 'days', label: 'Daily' },
    ...(this.canApprove ? [{ value: 'approvals' as View, label: 'Approvals' }] : []),
  ];
  protected readonly statusLabels = REMOTE_STATUS_LABELS;
  protected readonly statusClasses = REMOTE_STATUS_CLASSES;
  protected readonly hours = hoursLabel;
  protected readonly time = time12;

  protected readonly view = signal<View>('people');
  protected readonly month = signal(isoDate(bdToday()).slice(0, 7));
  protected readonly date = signal(isoDate(bdToday()));
  protected readonly userId = signal<number | null>(null);
  protected readonly exporting = signal(false);
  protected readonly saving = signal(false);
  protected readonly reviewing = signal<RemoteDay | null>(null);
  protected readonly draft = signal<SessionDraft | null>(null);
  protected fee: number | null = 0;
  protected reviewNote = '';

  protected readonly peoplePageNo = linkedSignal({ source: this.month, computation: () => 0 });
  protected readonly daysPageNo = linkedSignal({ source: () => [this.userId(), this.month(), this.date()], computation: () => 0 });
  protected readonly pendingPageNo = linkedSignal({ source: this.userId, computation: () => 0 });

  /** Everyone remote, for the filter; the cards list is paged separately. */
  private readonly all = rxResource({ params: () => this.month(), stream: ({ params }) => this.api.people(params, 0, 100) });
  protected readonly people = rxResource({
    params: () => ({ month: this.month(), page: this.peoplePageNo() }),
    stream: ({ params }) => this.api.people(params.month, params.page, 12),
  });
  private readonly totals = rxResource({
    params: () => ({ month: this.month(), userId: this.userId() }),
    stream: ({ params }) => this.api.summary(params.month, params.userId),
  });
  protected readonly days = rxResource({
    params: () => ({ date: this.date(), month: this.month(), userId: this.userId(), page: this.daysPageNo() }),
    stream: ({ params }) => this.api.days(params.date, params.month, params.userId, params.page, 20),
  });
  protected readonly pending = rxResource({
    params: () => (this.canApprove ? { userId: this.userId(), page: this.pendingPageNo() } : undefined),
    stream: ({ params }) => this.api.pending(params.userId, params.page, 20),
  });

  protected readonly everyone = computed(() => (this.all.hasValue() ? this.all.value().content : []));
  protected readonly peoplePage = computed(() => (this.people.hasValue() ? this.people.value() : undefined));
  protected readonly daysPage = computed(() => (this.days.hasValue() ? this.days.value() : undefined));
  protected readonly pendingPage = computed(() => (this.pending.hasValue() ? this.pending.value() : undefined));
  protected readonly summary = computed(() => (this.totals.hasValue() ? this.totals.value() : undefined));
  protected readonly scope = computed(
    () => this.everyone().find((p) => p.userId === this.userId())?.fullName ?? 'All remote workers',
  );

  protected setMonth(value: string): void {
    if (value) this.month.set(value);
  }

  protected setDate(value: string): void {
    if (value) {
      this.date.set(value);
      this.month.set(value.slice(0, 7));
    }
  }

  protected openPerson(userId: number): void {
    this.userId.set(userId);
    this.view.set('days');
  }

  protected startReview(d: RemoteDay): void {
    this.fee = d.lateFee;
    this.reviewNote = d.reviewNote ?? '';
    this.reviewing.set(d);
  }

  protected saveReview(fee: number | null): void {
    const d = this.reviewing();
    if (!d?.id) return;
    this.saving.set(true);
    this.api.review(d.id, fee || 0, this.reviewNote.trim() || null).subscribe({
      next: () => {
        this.toast.success(d.reviewStatus === 'PENDING' ? 'Approved' : 'Late fee saved');
        this.reviewing.set(null);
        this.saving.set(false);
        this.reloadAll();
      },
      error: (err: unknown) => {
        this.toast.error(errorMessage(err));
        this.saving.set(false);
      },
    });
  }

  protected addSession(d: RemoteDay): void {
    this.draft.set({ id: null, userId: d.userId, fullName: d.fullName, date: d.date, on: '', off: '', note: '' });
  }

  protected editSession(d: RemoteDay, s: RemoteSession): void {
    this.draft.set({
      id: s.id,
      userId: d.userId,
      fullName: d.fullName,
      date: d.date,
      on: toBdInputValue(s.onAt).slice(11, 16),
      off: s.offAt ? toBdInputValue(s.offAt).slice(11, 16) : '',
      note: '',
    });
  }

  protected saveSession(s: SessionDraft): void {
    const body = { userId: s.userId, date: s.date, on: s.on, off: s.off || null, note: s.note.trim() };
    this.saving.set(true);
    (s.id === null ? this.api.addSession(body) : this.api.updateSession(s.id, body)).subscribe({
      next: () => {
        this.toast.success('Session saved');
        this.draft.set(null);
        this.saving.set(false);
        this.reloadAll();
      },
      error: (err: unknown) => {
        this.toast.error(errorMessage(err));
        this.saving.set(false);
      },
    });
  }

  protected async deleteSession(s: RemoteSession): Promise<void> {
    const ok = await this.confirm.ask({ title: 'Delete this session?', message: 'The time worked is recalculated.', danger: true });
    if (!ok) return;
    this.api.deleteSession(s.id).subscribe({ next: () => this.reloadAll(), error: (err: unknown) => this.toast.error(errorMessage(err)) });
  }

  protected async deleteDay(d: RemoteDay): Promise<void> {
    const ok = await this.confirm.ask({
      title: `Delete ${d.fullName}'s day?`,
      message: 'All of its sessions and any late fee are removed. This cannot be undone.',
      danger: true,
    });
    if (!ok || !d.id) return;
    this.api.deleteDay(d.id).subscribe({ next: () => this.reloadAll(), error: (err: unknown) => this.toast.error(errorMessage(err)) });
  }

  protected exportExcel(): void {
    this.exporting.set(true);
    this.api.exportMonth(this.month()).subscribe({
      next: (response) => {
        saveBlob(response.body!, fileNameFrom(response, `remote-attendance-${this.month()}.xlsx`));
        this.exporting.set(false);
      },
      error: (err: unknown) => {
        this.toast.error(errorMessage(err));
        this.exporting.set(false);
      },
    });
  }

  private reloadAll(): void {
    this.days.reload();
    this.pending.reload();
    this.people.reload();
    this.all.reload();
    this.totals.reload();
  }
}
