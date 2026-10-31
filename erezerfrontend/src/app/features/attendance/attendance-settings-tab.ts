import { ChangeDetectionStrategy, Component, computed, inject, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { errorMessage } from '../../core/http/api-error';
import { ToastService } from '../../core/notify/toast.service';
import { ConfirmService } from '../../shared/ui/confirm-dialog';
import { Pagination } from '../../shared/ui/pagination';
import { AttendanceSpecialHours } from './attendance-special-hours';
import { AttendanceApi, LocationRequest } from './data/attendance.api';
import { currentPosition, Shift, WEEK_DAYS, WeekDay, WorkLocation } from './data/attendance.models';

const PAGE_SIZE = 10;

interface ShiftDraft {
  userId: number;
  fullName: string;
  checkIn: string;
  checkOut: string;
  graceMinutes: number;
  weeklyOff: WeekDay[];
  saved: boolean;
  canCheckIn: boolean;
}

interface LocationDraft {
  id: number | null;
  name: string;
  latitude: number | null;
  longitude: number | null;
  radiusM: number;
  active: boolean;
  used: boolean;
}

/** Each employee's hours and days off, and the places attendance can be given from. */
@Component({
  selector: 'app-attendance-settings-tab',
  imports: [AttendanceSpecialHours, FormsModule, Pagination],
  template: `
    <section class="card overflow-hidden">
      <div class="border-b border-line p-4">
        <h2 class="text-lg font-semibold text-white">Employees</h2>
        <p class="text-sm text-slate-400">Set each person's check-in and check-out time and their days off.</p>
      </div>
      <div class="divide-y divide-line/60">
        @for (s of shiftsOnPage(); track s.userId) {
          <div class="grid gap-3 p-4 lg:grid-cols-[minmax(0,1fr)_8rem_8rem_6rem_auto_auto] lg:items-end">
            <p class="font-medium text-white lg:self-center">
              {{ s.fullName }}
              @if (!s.saved) { <span class="block text-xs font-normal text-amber-300">No hours set yet</span> }
              @if (s.saved && !s.canCheckIn) { <span class="block text-xs font-normal text-amber-300">Can't check in: give them the attendance permission in Staff Management</span> }
            </p>
            <div><label class="field-label" [for]="'in-' + s.userId">Check in</label><input [id]="'in-' + s.userId" type="time" class="field" [name]="'in' + s.userId" [(ngModel)]="s.checkIn" /></div>
            <div><label class="field-label" [for]="'out-' + s.userId">Check out</label><input [id]="'out-' + s.userId" type="time" class="field" [name]="'out' + s.userId" [(ngModel)]="s.checkOut" /></div>
            <div><label class="field-label" [for]="'grace-' + s.userId">Grace (min)</label><input [id]="'grace-' + s.userId" type="number" min="0" max="240" class="field" [name]="'grace' + s.userId" [(ngModel)]="s.graceMinutes" /></div>
            <div>
              <p class="field-label">Days off</p>
              <div class="flex flex-wrap gap-1">
                @for (d of days; track d.value) {
                  <button type="button" class="rounded border px-2 py-2 text-xs transition" [attr.aria-pressed]="s.weeklyOff.includes(d.value)"
                    [class]="s.weeklyOff.includes(d.value) ? 'border-brand bg-brand/20 text-white' : 'border-line text-slate-400 hover:text-white'"
                    (click)="toggleDay(s, d.value)">{{ d.label }}</button>
                }
              </div>
            </div>
            <div class="flex gap-2">
              <button type="button" class="btn-primary" [disabled]="!s.checkIn || !s.checkOut" (click)="saveShift(s)">Save</button>
              @if (s.saved) {
                <button type="button" class="btn-secondary" title="Remove from attendance" aria-label="Remove from attendance" (click)="removeShift(s)"><i class="fas fa-times"></i></button>
              }
            </div>
          </div>
        } @empty {
          <p class="p-6 text-center text-slate-500">Nobody to show yet. Add employees in Staff Management.</p>
        }
      </div>
      @if (shifts().length) {
        <div class="border-t border-line px-4 py-3">
          <app-pagination [page]="shiftPage()" [size]="pageSize" [totalElements]="shifts().length" [totalPages]="pagesOf(shifts().length)" (pageChange)="shiftPage.set($event)" />
        </div>
      }
    </section>

    <app-attendance-special-hours />

    <section class="card mt-6 overflow-hidden">
      <div class="flex flex-wrap items-center justify-between gap-3 border-b border-line p-4">
        <div>
          <h2 class="text-lg font-semibold text-white">Locations</h2>
          <p class="text-sm text-slate-400">Stand at the place and press "Use my location", or paste its latitude and longitude.</p>
        </div>
        <button type="button" class="btn-secondary" (click)="addLocation()"><i class="fas fa-plus"></i> Add place</button>
      </div>
      <div class="divide-y divide-line/60">
        @for (l of locationsOnPage(); track l) {
          <div class="grid gap-3 p-4 lg:grid-cols-[minmax(0,1fr)_9rem_9rem_6rem_auto] lg:items-end">
            <div><label class="field-label" [for]="'name-' + $index">Name</label><input [id]="'name-' + $index" class="field" maxlength="80" [name]="'name' + $index" [(ngModel)]="l.name" /></div>
            <div><label class="field-label" [for]="'lat-' + $index">Latitude</label><input [id]="'lat-' + $index" type="number" step="any" class="field" [name]="'lat' + $index" [(ngModel)]="l.latitude" /></div>
            <div><label class="field-label" [for]="'lng-' + $index">Longitude</label><input [id]="'lng-' + $index" type="number" step="any" class="field" [name]="'lng' + $index" [(ngModel)]="l.longitude" /></div>
            <div><label class="field-label" [for]="'radius-' + $index">Radius (m)</label><input [id]="'radius-' + $index" type="number" min="10" max="5000" class="field" [name]="'radius' + $index" [(ngModel)]="l.radiusM" /></div>
            <div class="flex flex-wrap items-center gap-2">
              <button type="button" class="btn-secondary" [disabled]="locating()" (click)="useMyLocation(l)"><i class="fas fa-location-arrow"></i> Use my location</button>
              <label class="flex items-center gap-1 text-sm text-slate-300"><input type="checkbox" [name]="'active' + $index" [(ngModel)]="l.active" /> On</label>
              <button type="button" class="btn-primary" [disabled]="!l.name.trim() || !l.radiusM" (click)="saveLocation(l)">Save</button>
              @if (!l.used) {
                <button type="button" class="btn-secondary" title="Delete" aria-label="Delete location" (click)="deleteLocation(l)"><i class="fas fa-trash"></i></button>
              }
            </div>
            @if (l.latitude == null) {
              <p class="text-xs text-amber-300 lg:col-span-5">No position yet. Until one is set, nobody can be recognised as being here.</p>
            }
          </div>
        }
      </div>
      @if (locations().length) {
        <div class="border-t border-line px-4 py-3">
          <app-pagination [page]="locationPage()" [size]="pageSize" [totalElements]="locations().length" [totalPages]="pagesOf(locations().length)" (pageChange)="locationPage.set($event)" />
        </div>
      }
    </section>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AttendanceSettingsTab {
  private readonly api = inject(AttendanceApi);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);

  /** Someone's hours were saved or removed, so the employee filter must refresh. */
  readonly changed = output<void>();

  protected readonly days = WEEK_DAYS;
  protected readonly pageSize = PAGE_SIZE;
  protected readonly shiftPage = signal(0);
  protected readonly locationPage = signal(0);
  protected readonly shifts = signal<ShiftDraft[]>([]);
  protected readonly locations = signal<LocationDraft[]>([]);
  protected readonly locating = signal(false);
  protected readonly shiftsOnPage = computed(() => onPage(this.shifts(), this.shiftPage()));
  protected readonly locationsOnPage = computed(() => onPage(this.locations(), this.locationPage()));

  constructor() {
    this.loadShifts();
    this.api.locations().subscribe({
      next: (rows) => this.showLocations(rows),
      error: (err: unknown) => this.toast.error(errorMessage(err)),
    });
  }

  private loadShifts(): void {
    this.api.shifts().subscribe({
      next: (rows) => {
        this.shifts.set(rows.map(toShiftDraft));
        this.shiftPage.update((p) => Math.min(p, Math.max(this.pagesOf(rows.length) - 1, 0)));
      },
      error: (err: unknown) => this.toast.error(errorMessage(err)),
    });
  }

  private showLocations(rows: WorkLocation[]): void {
    this.locations.set(rows.map((l) => ({ ...l, latitude: l.latitude ?? null, longitude: l.longitude ?? null })));
    this.locationPage.update((p) => Math.min(p, Math.max(this.pagesOf(rows.length) - 1, 0)));
  }

  protected pagesOf(count: number): number {
    return Math.ceil(count / PAGE_SIZE);
  }

  protected toggleDay(s: ShiftDraft, day: WeekDay): void {
    s.weeklyOff = s.weeklyOff.includes(day) ? s.weeklyOff.filter((d) => d !== day) : [...s.weeklyOff, day];
  }

  protected saveShift(s: ShiftDraft): void {
    this.api
      .setShift(s.userId, { checkIn: s.checkIn, checkOut: s.checkOut, graceMinutes: s.graceMinutes || 0, weeklyOff: s.weeklyOff })
      .subscribe({
        next: () => {
          this.toast.success(`Saved ${s.fullName}'s hours`);
          this.loadShifts();
          this.changed.emit();
        },
        error: (err: unknown) => this.toast.error(errorMessage(err)),
      });
  }

  protected async removeShift(s: ShiftDraft): Promise<void> {
    const confirmed = await this.confirm.ask({
      title: `Remove ${s.fullName} from attendance?`,
      message: 'They will no longer be counted. Past days are kept.',
      danger: true,
    });
    if (!confirmed) return;
    this.api.removeShift(s.userId).subscribe({
      next: () => {
        this.loadShifts();
        this.changed.emit();
      },
      error: (err: unknown) => this.toast.error(errorMessage(err)),
    });
  }

  protected addLocation(): void {
    this.locations.update((rows) => [
      ...rows,
      { id: null, name: '', latitude: null, longitude: null, radiusM: 100, active: true, used: false },
    ]);
    this.locationPage.set(this.pagesOf(this.locations().length) - 1);
  }

  protected async useMyLocation(l: LocationDraft): Promise<void> {
    this.locating.set(true);
    const position = await currentPosition();
    this.locating.set(false);
    if (!position) {
      this.toast.error('Your location could not be read. Allow location access for this site, or type it in.');
      return;
    }
    l.latitude = position.latitude;
    l.longitude = position.longitude;
    this.locations.update((rows) => [...rows]);
  }

  protected saveLocation(l: LocationDraft): void {
    const body: LocationRequest = { name: l.name, latitude: l.latitude, longitude: l.longitude, radiusM: l.radiusM, active: l.active };
    (l.id === null ? this.api.createLocation(body) : this.api.updateLocation(l.id, body)).subscribe({
      next: (rows) => {
        this.toast.success('Location saved');
        this.showLocations(rows);
      },
      error: (err: unknown) => this.toast.error(errorMessage(err)),
    });
  }

  protected async deleteLocation(l: LocationDraft): Promise<void> {
    if (l.id === null) {
      this.locations.update((rows) => rows.filter((r) => r !== l));
      this.locationPage.update((p) => Math.min(p, Math.max(this.pagesOf(this.locations().length) - 1, 0)));
      return;
    }
    const confirmed = await this.confirm.ask({ title: `Delete ${l.name}?`, message: 'This cannot be undone.', danger: true });
    if (!confirmed) return;
    this.api.deleteLocation(l.id).subscribe({
      next: (rows) => this.showLocations(rows),
      error: (err: unknown) => this.toast.error(errorMessage(err)),
    });
  }
}

function onPage<T>(rows: T[], page: number): T[] {
  return rows.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE);
}

function toShiftDraft(s: Shift): ShiftDraft {
  return {
    userId: s.userId,
    fullName: s.fullName,
    checkIn: s.checkIn?.slice(0, 5) ?? '',
    checkOut: s.checkOut?.slice(0, 5) ?? '',
    graceMinutes: s.graceMinutes,
    weeklyOff: s.weeklyOff ?? [],
    saved: !!s.checkIn,
    canCheckIn: s.canCheckIn,
  };
}
