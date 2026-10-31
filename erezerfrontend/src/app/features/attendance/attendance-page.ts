import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../core/auth/auth.service';
import { Permission } from '../../core/auth/auth.models';
import { isoDate } from '../../shared/date-range';
import { BdDatePipe, bdToday } from '../../shared/ui/bd-date.pipe';
import { TakaPipe } from '../../shared/ui/taka.pipe';
import { AttendanceApprovalsTab } from './attendance-approvals-tab';
import { AttendanceDayTab } from './attendance-day-tab';
import { AttendanceMonthTab } from './attendance-month-tab';
import { AttendanceSettingsTab } from './attendance-settings-tab';
import { AttendanceApi } from './data/attendance.api';
import { hoursLabel } from './data/attendance.models';

type Tab = 'day' | 'approvals' | 'month' | 'settings';

const TABS: readonly { value: Tab; label: string; icon: string; permission: Permission }[] = [
  { value: 'day', label: 'Daily', icon: 'fa-calendar-day', permission: 'ATTENDANCE_VIEW' },
  { value: 'approvals', label: 'Approvals', icon: 'fa-check-double', permission: 'ATTENDANCE_APPROVE' },
  { value: 'month', label: 'Monthly', icon: 'fa-table', permission: 'ATTENDANCE_VIEW' },
  { value: 'settings', label: 'Settings', icon: 'fa-cog', permission: 'ATTENDANCE_SETTINGS' },
];

/** Everyone's attendance. The employee and month chosen here drive the cards and the lists below. */
@Component({
  selector: 'app-attendance-page',
  imports: [AttendanceApprovalsTab, AttendanceDayTab, AttendanceMonthTab, AttendanceSettingsTab, BdDatePipe, FormsModule, TakaPipe],
  template: `
    <h1 class="page-title">Attendance</h1>
    <p class="page-subtitle">Check-ins with photo and location, approvals, late fees and each employee's hours.</p>

    @if (tab() !== 'settings') {
      <div class="mt-6 flex flex-wrap gap-3">
        <select class="field w-full sm:w-64" aria-label="Employee" [ngModel]="userId()" (ngModelChange)="userId.set($event)">
          <option [ngValue]="null">All employees</option>
          @for (e of employees(); track e.userId) {
            <option [ngValue]="e.userId">{{ e.fullName }}</option>
          }
        </select>
        <input type="month" class="field w-full sm:w-auto" [ngModel]="month()" (ngModelChange)="setMonth($event)" aria-label="Month" />
      </div>

      <div class="mt-4 grid gap-4 sm:grid-cols-2">
        <div class="card p-5">
          <p class="text-sm text-slate-400"><i class="fas fa-clock mr-1 text-sky-300"></i>Total working hours</p>
          <p class="mt-1 text-2xl font-bold text-white">{{ hours(summary()?.workedMinutes ?? 0) }}</p>
          <p class="mt-1 text-xs text-slate-500">{{ scope() }} · {{ month() + '-01' | bdDate: 'MMMM y' }}</p>
        </div>
        <div class="card p-5">
          <p class="text-sm text-slate-400"><i class="fas fa-coins mr-1 text-rose-300"></i>Total late fee</p>
          <p class="mt-1 text-2xl font-bold text-white">{{ summary()?.lateFees ?? 0 | taka }}</p>
          <p class="mt-1 text-xs text-slate-500">{{ scope() }} · {{ month() + '-01' | bdDate: 'MMMM y' }}</p>
        </div>
      </div>
    }

    <div class="mt-6 flex flex-wrap gap-2" role="tablist">
      @for (t of tabs; track t.value) {
        <button type="button" role="tab" [attr.aria-selected]="tab() === t.value" [class]="tab() === t.value ? 'btn-primary' : 'btn-secondary'" (click)="tab.set(t.value)">
          <i class="fas" [class]="t.icon"></i> {{ t.label }}
        </button>
      }
    </div>

    <div class="mt-4">
      @switch (tab()) {
        @case ('day') { <app-attendance-day-tab [month]="month()" [userId]="userId()" (changed)="data.reload()" /> }
        @case ('approvals') { <app-attendance-approvals-tab [userId]="userId()" (changed)="data.reload()" /> }
        @case ('month') { <app-attendance-month-tab [month]="month()" [userId]="userId()" /> }
        @case ('settings') { <app-attendance-settings-tab (changed)="people.reload()" /> }
      }
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AttendancePage {
  private readonly auth = inject(AuthService);
  private readonly api = inject(AttendanceApi);

  protected readonly tabs = TABS.filter((t) => this.auth.hasPermission(t.permission));
  protected readonly tab = signal<Tab>('day');
  protected readonly hours = hoursLabel;
  protected readonly month = signal(isoDate(bdToday()).slice(0, 7));
  protected readonly userId = signal<number | null>(null);

  protected readonly people = rxResource({ stream: () => this.api.employees() });
  protected readonly data = rxResource({
    params: () => ({ month: this.month(), userId: this.userId() }),
    stream: ({ params }) => this.api.summary(params.month, params.userId),
  });

  protected readonly employees = computed(() => (this.people.hasValue() ? this.people.value() : []));
  protected readonly summary = computed(() => (this.data.hasValue() ? this.data.value() : undefined));
  /** Whose totals the cards are showing. */
  protected readonly scope = computed(
    () => this.employees().find((e) => e.userId === this.userId())?.fullName ?? 'All employees',
  );

  protected setMonth(value: string): void {
    if (value) this.month.set(value);
  }
}
