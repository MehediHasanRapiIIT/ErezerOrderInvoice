import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { errorMessage } from '../../core/http/api-error';
import { ToastService } from '../../core/notify/toast.service';
import { NotificationApi, Rule } from './data/notification.api';

type Switch = 'enabled' | 'push' | 'fromModerator' | 'fromEmployee' | 'fromAdmin';

const SWITCHES: readonly { key: Switch; label: string }[] = [
  { key: 'enabled', label: 'On' },
  { key: 'push', label: 'Push' },
  { key: 'fromModerator', label: 'Moderator' },
  { key: 'fromEmployee', label: 'Employee' },
  { key: 'fromAdmin', label: 'Admin' },
];

/** How every action behaves: whether it notifies, whether it pushes, and whose actions count. */
@Component({
  selector: 'app-notifications-rules-tab',
  imports: [FormsModule],
  template: `
    <div class="card mb-4 p-4 text-sm text-slate-300">
      <p><b class="text-white">On</b> sends a notification for this action. <b class="text-white">Push</b> also sends it to phones.
        <b class="text-white">Moderator, Employee, Admin</b> choose whose actions count.</p>
      <p class="mt-1 text-slate-500">Who receives each action is set in the People tab.</p>
    </div>

    @for (group of groups(); track group.section) {
      <section class="card mb-4 overflow-hidden">
        <div class="flex flex-wrap items-center gap-x-4 gap-y-2 border-b border-line p-4">
          <h2 class="mr-auto font-semibold text-white">{{ group.section }}</h2>
          @for (s of switches; track s.key) {
            <label class="flex items-center gap-1.5 text-xs text-slate-400">
              <input type="checkbox" class="accent-blue-600" [checked]="allOn(group.rules, s.key)" (change)="setAll(group.rules, s.key, $any($event.target).checked)" [attr.aria-label]="'All ' + s.label + ' in ' + group.section" />
              All {{ s.label }}
            </label>
          }
        </div>
        <ul class="divide-y divide-line/60">
          @for (r of group.rules; track r.action) {
            <li class="flex flex-wrap items-center gap-x-5 gap-y-2 px-4 py-3 text-sm">
              <span class="min-w-48 flex-1" [class]="r.enabled ? 'text-white' : 'text-slate-500'">
                {{ r.label }}
                <span class="block text-xs text-slate-500">{{ r.recipients }} {{ r.recipients === 1 ? 'person' : 'people' }} receive this</span>
              </span>
              @for (s of switches; track s.key) {
                <label class="flex items-center gap-1.5 text-slate-300">
                  <input type="checkbox" class="accent-blue-600" [(ngModel)]="r[s.key]" [attr.aria-label]="s.label + ': ' + r.label" />
                  {{ s.label }}
                </label>
              }
            </li>
          }
        </ul>
      </section>
    } @empty {
      <p class="card p-10 text-center text-slate-500">Loading…</p>
    }

    @if (rules().length) {
      <div class="sticky bottom-0 flex justify-end border-t border-line bg-app/95 py-3 backdrop-blur">
        <button type="button" class="btn-primary" [disabled]="saving()" (click)="save()">Save</button>
      </div>
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NotificationsRulesTab {
  private readonly api = inject(NotificationApi);
  private readonly toast = inject(ToastService);

  protected readonly switches = SWITCHES;
  protected readonly rules = signal<Rule[]>([]);
  protected readonly saving = signal(false);
  protected readonly groups = computed(() => {
    const sections = [...new Set(this.rules().map((r) => r.section))];
    return sections.map((section) => ({ section, rules: this.rules().filter((r) => r.section === section) }));
  });

  constructor() {
    this.api.rules().subscribe({
      next: (rules) => this.rules.set(rules),
      error: (err: unknown) => this.toast.error(errorMessage(err)),
    });
  }

  protected allOn(rules: Rule[], key: Switch): boolean {
    return rules.every((r) => r[key]);
  }

  protected setAll(rules: Rule[], key: Switch, value: boolean): void {
    rules.forEach((r) => (r[key] = value));
    this.rules.update((list) => [...list]);
  }

  protected save(): void {
    this.saving.set(true);
    this.api.saveRules(this.rules()).subscribe({
      next: () => {
        this.toast.success('Saved');
        this.saving.set(false);
      },
      error: (err: unknown) => {
        this.toast.error(errorMessage(err));
        this.saving.set(false);
      },
    });
  }
}
