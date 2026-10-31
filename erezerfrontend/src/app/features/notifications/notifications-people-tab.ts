import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { errorMessage } from '../../core/http/api-error';
import { ToastService } from '../../core/notify/toast.service';
import { ActionInfo, NotificationApi, Subscription } from './data/notification.api';

interface Row extends ActionInfo {
  see: boolean;
  push: boolean;
}

/** Exactly which actions each person sees, and which of them reach their phone. */
@Component({
  selector: 'app-notifications-people-tab',
  imports: [FormsModule],
  template: `
    <div class="card mb-4 p-4">
      <label class="field-label" for="notify-person">Person</label>
      <select id="notify-person" class="field" [ngModel]="personId()" (ngModelChange)="choose($event)">
        <option [ngValue]="null" disabled>Choose someone</option>
        @for (p of people(); track p.id) {
          <option [ngValue]="p.id">{{ p.fullName }} ({{ roleLabel(p.role) }}): {{ p.actions }} actions, {{ p.devices }} device{{ p.devices === 1 ? '' : 's' }}</option>
        }
      </select>
      <p class="mt-2 text-xs text-slate-500">Ticking anything lets this person open the Notifications menu. Unticking everything removes it.</p>
    </div>

    @if (personId() !== null) {
      <div class="mb-4 flex flex-wrap gap-2">
        <button type="button" class="btn-secondary" (click)="setEverything(true)"><i class="fas fa-check-double"></i> Tick everything</button>
        <button type="button" class="btn-secondary" (click)="setEverything(false)"><i class="fas fa-times"></i> Clear everything</button>
      </div>

      @for (group of groups(); track group.section) {
        <section class="card mb-4 overflow-hidden">
          <div class="flex flex-wrap items-center gap-x-4 gap-y-2 border-b border-line p-4">
            <h2 class="mr-auto font-semibold text-white">{{ group.section }}</h2>
            <label class="flex items-center gap-1.5 text-xs text-slate-400">
              <input type="checkbox" class="accent-blue-600" [checked]="allOn(group.rows, 'see')" (change)="setAll(group.rows, 'see', $any($event.target).checked)" [attr.aria-label]="'See all in ' + group.section" /> See all
            </label>
            <label class="flex items-center gap-1.5 text-xs text-slate-400">
              <input type="checkbox" class="accent-blue-600" [checked]="allOn(group.rows, 'push')" (change)="setAll(group.rows, 'push', $any($event.target).checked)" [attr.aria-label]="'Push all in ' + group.section" /> Push all
            </label>
          </div>
          <ul class="divide-y divide-line/60">
            @for (r of group.rows; track r.action) {
              <li class="flex flex-wrap items-center gap-x-5 gap-y-2 px-4 py-3 text-sm">
                <span class="min-w-48 flex-1 text-white">{{ r.label }}</span>
                <label class="flex items-center gap-1.5 text-slate-300">
                  <input type="checkbox" class="accent-blue-600" [ngModel]="r.see" (ngModelChange)="setSee(r, $event)" [attr.aria-label]="'See: ' + r.label" /> See
                </label>
                <label class="flex items-center gap-1.5 text-slate-300">
                  <input type="checkbox" class="accent-blue-600" [ngModel]="r.push" (ngModelChange)="setPush(r, $event)" [attr.aria-label]="'Push: ' + r.label" /> Push
                </label>
              </li>
            }
          </ul>
        </section>
      }

      <div class="sticky bottom-0 flex justify-end border-t border-line bg-app/95 py-3 backdrop-blur">
        <button type="button" class="btn-primary" [disabled]="saving()" (click)="save()">Save</button>
      </div>
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NotificationsPeopleTab {
  private readonly api = inject(NotificationApi);
  private readonly toast = inject(ToastService);

  protected readonly personId = signal<number | null>(null);
  protected readonly rows = signal<Row[]>([]);
  protected readonly saving = signal(false);

  private readonly peopleList = rxResource({ stream: () => this.api.people() });
  private readonly catalog = rxResource({ stream: () => this.api.actions() });
  protected readonly people = computed(() => (this.peopleList.hasValue() ? this.peopleList.value() : []));
  protected readonly groups = computed(() => {
    const sections = [...new Set(this.rows().map((r) => r.section))];
    return sections.map((section) => ({ section, rows: this.rows().filter((r) => r.section === section) }));
  });

  protected roleLabel(role: string): string {
    return role === 'ADMIN' ? 'Admin' : role === 'EMPLOYEE' ? 'Employee' : 'Moderator';
  }

  protected choose(id: number): void {
    this.personId.set(id);
    this.api.subscriptions(id).subscribe({
      next: (list) => {
        const byAction = new Map(list.map((s) => [s.action, s]));
        const actions = this.catalog.hasValue() ? this.catalog.value() : [];
        this.rows.set(actions.map((a) => ({ ...a, see: byAction.get(a.action)?.see ?? false, push: byAction.get(a.action)?.push ?? false })));
      },
      error: (err: unknown) => this.toast.error(errorMessage(err)),
    });
  }

  /** Pushing something means seeing it, and not seeing it means no push. */
  protected setSee(row: Row, value: boolean): void {
    row.see = value;
    row.push = value && row.push;
    this.rows.update((list) => [...list]);
  }

  protected setPush(row: Row, value: boolean): void {
    row.push = value;
    row.see = value || row.see;
    this.rows.update((list) => [...list]);
  }

  protected allOn(rows: Row[], key: 'see' | 'push'): boolean {
    return rows.every((r) => r[key]);
  }

  protected setAll(rows: Row[], key: 'see' | 'push', value: boolean): void {
    rows.forEach((r) => (key === 'see' ? this.setSee(r, value) : this.setPush(r, value)));
  }

  protected setEverything(value: boolean): void {
    this.rows.update((list) => list.map((r) => ({ ...r, see: value, push: value })));
  }

  protected save(): void {
    const id = this.personId();
    if (id === null) return;
    const body: Subscription[] = this.rows().map(({ action, see, push }) => ({ action, see, push }));
    this.saving.set(true);
    this.api.saveSubscriptions(id, body).subscribe({
      next: () => {
        this.toast.success('Saved');
        this.saving.set(false);
        this.peopleList.reload();
      },
      error: (err: unknown) => {
        this.toast.error(errorMessage(err));
        this.saving.set(false);
      },
    });
  }
}
