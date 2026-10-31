import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { errorMessage } from '../../core/http/api-error';
import { ToastService } from '../../core/notify/toast.service';
import { BdDatePipe } from '../../shared/ui/bd-date.pipe';
import { ConfirmService } from '../../shared/ui/confirm-dialog';
import { Pagination } from '../../shared/ui/pagination';
import { AttendanceApi } from './data/attendance.api';
import { SpecialHours, time12, WEEK_DAYS, WeekDay } from './data/attendance.models';

const PAGE_SIZE = 10;

/** Hours that differ from the normal shift, for one person or everyone, on certain dates or a weekday. */
@Component({
  selector: 'app-attendance-special-hours',
  imports: [BdDatePipe, FormsModule, Pagination],
  template: `
    <section class="card mt-6 overflow-hidden">
      <div class="border-b border-line p-4">
        <h2 class="text-lg font-semibold text-white">Special hours</h2>
        <p class="text-sm text-slate-400">Different check-in and check-out times on certain dates or a weekday, or a day off such as a holiday.</p>
      </div>

      <form class="grid gap-3 border-b border-line p-4 sm:grid-cols-2 lg:grid-cols-4" (ngSubmit)="save()">
        <div>
          <label class="field-label" for="special-who">Who</label>
          <select id="special-who" name="who" class="field" [(ngModel)]="userId">
            <option [ngValue]="null">Everyone</option>
            @for (p of people(); track p.userId) { <option [ngValue]="p.userId">{{ p.fullName }}</option> }
          </select>
        </div>
        <div>
          <label class="field-label" for="special-when">When</label>
          <select id="special-when" name="when" class="field" [ngModel]="mode()" (ngModelChange)="mode.set($event)">
            <option value="date">Date or dates</option>
            <option value="week">Every week on</option>
          </select>
        </div>
        @if (mode() === 'date') {
          <div>
            <label class="field-label" for="special-from">From *</label>
            <input id="special-from" name="from" type="date" class="field" [(ngModel)]="dateFrom" />
          </div>
          <div>
            <label class="field-label" for="special-to">To (leave empty for one day)</label>
            <input id="special-to" name="to" type="date" class="field" [min]="dateFrom || null" [(ngModel)]="dateTo" />
          </div>
        } @else {
          <div>
            <label class="field-label" for="special-day">Weekday *</label>
            <select id="special-day" name="day" class="field" [(ngModel)]="weekDay">
              @for (d of days; track d.value) { <option [ngValue]="d.value">{{ d.label }}</option> }
            </select>
          </div>
        }
        <label class="flex items-center gap-2 self-end pb-3 text-sm text-slate-300">
          <input type="checkbox" name="off" class="accent-blue-600" [ngModel]="dayOff()" (ngModelChange)="dayOff.set($event)" /> Day off
        </label>
        @if (!dayOff()) {
          <div>
            <label class="field-label" for="special-in">Check in *</label>
            <input id="special-in" name="in" type="time" class="field" [(ngModel)]="checkIn" />
          </div>
          <div>
            <label class="field-label" for="special-out">Check out *</label>
            <input id="special-out" name="out" type="time" class="field" [(ngModel)]="checkOut" />
          </div>
        }
        <div>
          <label class="field-label" for="special-note">Note</label>
          <input id="special-note" name="note" class="field" maxlength="120" placeholder="e.g. Ramadan" [(ngModel)]="note" />
        </div>
        <div class="flex items-end gap-2">
          <button type="submit" class="btn-primary" [disabled]="saving()">{{ editingId() ? 'Save changes' : 'Add' }}</button>
          @if (editingId()) { <button type="button" class="btn-secondary" (click)="reset()">Cancel</button> }
        </div>
      </form>

      <ul class="divide-y divide-line/60 text-sm">
        @for (s of onPage(); track s.id) {
          <li class="flex flex-wrap items-center gap-3 p-4">
            <span class="min-w-0 flex-1">
              <span class="block font-medium text-white">
                @if (s.weekDay) { Every {{ dayLabel(s.weekDay) }} }
                @else if (s.dateFrom === s.dateTo) { {{ s.dateFrom | bdDate: 'd MMM y' }} }
                @else { {{ s.dateFrom | bdDate: 'd MMM y' }} to {{ s.dateTo | bdDate: 'd MMM y' }} }
              </span>
              <span class="block text-slate-300">{{ s.dayOff ? 'Day off' : time(s.checkIn) + ' – ' + time(s.checkOut) }}</span>
              <span class="block text-xs text-slate-500">{{ s.personName || 'Everyone' }}{{ s.note ? ' · ' + s.note : '' }}</span>
            </span>
            <button type="button" class="btn-secondary" [attr.aria-label]="'Edit special hours'" (click)="edit(s)"><i class="fas fa-pen"></i></button>
            <button type="button" class="btn-secondary" [attr.aria-label]="'Delete special hours'" (click)="remove(s)"><i class="fas fa-trash"></i></button>
          </li>
        } @empty {
          <li class="p-6 text-center text-slate-500">None yet. Everyone follows their normal hours.</li>
        }
      </ul>
      @if (rows().length) {
        <div class="border-t border-line px-4 py-3">
          <app-pagination [page]="page()" [size]="pageSize" [totalElements]="rows().length" [totalPages]="pages()" (pageChange)="page.set($event)" />
        </div>
      }
    </section>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AttendanceSpecialHours {
  private readonly api = inject(AttendanceApi);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);

  protected readonly days = WEEK_DAYS;
  protected readonly time = time12;
  protected readonly pageSize = PAGE_SIZE;
  protected readonly mode = signal<'date' | 'week'>('date');
  protected readonly dayOff = signal(false);
  protected readonly editingId = signal<number | null>(null);
  protected readonly saving = signal(false);
  protected readonly page = signal(0);
  protected userId: number | null = null;
  protected dateFrom = '';
  protected dateTo = '';
  protected weekDay: WeekDay = 'FRIDAY';
  protected checkIn = '';
  protected checkOut = '';
  protected note = '';

  private readonly list = rxResource({ stream: () => this.api.specialHours() });
  private readonly shifts = rxResource({ stream: () => this.api.shifts() });
  protected readonly rows = computed(() => (this.list.hasValue() ? this.list.value() : []));
  protected readonly people = computed(() => (this.shifts.hasValue() ? this.shifts.value() : []));
  protected readonly pages = computed(() => Math.ceil(this.rows().length / PAGE_SIZE));
  protected readonly onPage = computed(() => this.rows().slice(this.page() * PAGE_SIZE, (this.page() + 1) * PAGE_SIZE));

  protected dayLabel(day: WeekDay): string {
    return this.days.find((d) => d.value === day)?.label ?? day;
  }

  protected edit(s: SpecialHours): void {
    this.editingId.set(s.id);
    this.userId = s.userId ?? null;
    this.mode.set(s.weekDay ? 'week' : 'date');
    this.weekDay = s.weekDay ?? 'FRIDAY';
    this.dateFrom = s.dateFrom ?? '';
    this.dateTo = s.dateTo && s.dateTo !== s.dateFrom ? s.dateTo : '';
    this.dayOff.set(s.dayOff);
    this.checkIn = s.checkIn?.slice(0, 5) ?? '';
    this.checkOut = s.checkOut?.slice(0, 5) ?? '';
    this.note = s.note ?? '';
  }

  protected reset(): void {
    this.editingId.set(null);
    this.dayOff.set(false);
    this.userId = null;
    this.dateFrom = '';
    this.dateTo = '';
    this.checkIn = '';
    this.checkOut = '';
    this.note = '';
  }

  protected save(): void {
    if (this.mode() === 'date' && !this.dateFrom) {
      this.toast.error('Choose the date');
      return;
    }
    if (!this.dayOff() && (!this.checkIn || !this.checkOut)) {
      this.toast.error('Enter the check-in and check-out times');
      return;
    }
    const byDate = this.mode() === 'date';
    const body = {
      userId: this.userId,
      weekDay: byDate ? null : this.weekDay,
      dateFrom: byDate ? this.dateFrom : null,
      dateTo: byDate ? this.dateTo || this.dateFrom : null,
      dayOff: this.dayOff(),
      checkIn: this.dayOff() ? null : this.checkIn,
      checkOut: this.dayOff() ? null : this.checkOut,
      note: this.note.trim() || null,
    };
    const id = this.editingId();
    this.saving.set(true);
    (id ? this.api.updateSpecialHours(id, body) : this.api.addSpecialHours(body)).subscribe({
      next: () => {
        this.toast.success('Saved');
        this.saving.set(false);
        this.reset();
        this.list.reload();
      },
      error: (err: unknown) => {
        this.toast.error(errorMessage(err));
        this.saving.set(false);
      },
    });
  }

  protected async remove(s: SpecialHours): Promise<void> {
    const confirmed = await this.confirm.ask({ title: 'Delete these special hours?', message: 'Those days go back to the normal hours.', danger: true });
    if (!confirmed) return;
    this.api.deleteSpecialHours(s.id).subscribe({
      next: () => {
        this.page.set(0);
        this.list.reload();
      },
      error: (err: unknown) => this.toast.error(errorMessage(err)),
    });
  }
}
