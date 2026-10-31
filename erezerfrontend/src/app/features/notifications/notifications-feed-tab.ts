import { ChangeDetectionStrategy, Component, computed, inject, linkedSignal, OnInit, output, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { of } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { errorMessage } from '../../core/http/api-error';
import { ToastService } from '../../core/notify/toast.service';
import { BdDatePipe } from '../../shared/ui/bd-date.pipe';
import { Pagination } from '../../shared/ui/pagination';
import { NotificationApi, NotificationFilter, NotificationItem } from './data/notification.api';
import { PushService } from './data/push.service';

const PAGE_SIZE = 20;

/** The person's own notifications, and turning pushes on for this device. */
@Component({
  selector: 'app-notifications-feed-tab',
  imports: [BdDatePipe, FormsModule, Pagination],
  template: `
    <div class="card mb-4 flex flex-wrap items-center justify-between gap-3 p-4 text-sm">
      @switch (push.state()) {
        @case ('on') {
          <p class="text-emerald-300"><i class="fas fa-bell mr-2"></i>Push notifications are on for this device.</p>
          <button type="button" class="btn-secondary" (click)="turnOff()">Turn off</button>
        }
        @case ('off') {
          <p class="text-slate-300"><i class="fas fa-bell-slash mr-2 text-slate-500"></i>Get a notification on this device, even when the panel is closed.</p>
          <button type="button" class="btn-primary" [disabled]="busy()" (click)="turnOn()"><i class="fas fa-bell"></i> Enable notifications</button>
        }
        @case ('blocked') {
          <p class="text-amber-300"><i class="fas fa-exclamation-triangle mr-2"></i>Notifications are blocked. Allow them for this site in your browser settings.</p>
        }
        @case ('install-needed') {
          <p class="text-slate-300"><i class="fas fa-mobile-alt mr-2"></i>On iPhone, first tap Share, then "Add to Home Screen", and open the panel from there.</p>
        }
        @case ('unsupported') {
          <p class="text-slate-400">This browser can't receive push notifications. Use Chrome or Edge.</p>
        }
        @case ('not-configured') {
          <p class="text-slate-400">Push notifications aren't set up yet. The list below still works.</p>
        }
        @default {
          <p class="text-slate-500">Checking this device…</p>
        }
      }
    </div>

    <section class="card overflow-hidden">
      <div class="grid gap-2 border-b border-line p-4 sm:grid-cols-2 lg:grid-cols-6">
        <select class="field" aria-label="Section" [ngModel]="section()" (ngModelChange)="section.set($event)">
          <option value="">All sections</option>
          @for (s of sections(); track s) { <option [value]="s">{{ s }}</option> }
        </select>
        @if (canManage) {
          <select class="field" aria-label="Person" [ngModel]="actorId()" (ngModelChange)="actorId.set($event)">
            <option [ngValue]="null">Everyone</option>
            @for (p of people(); track p.id) { <option [ngValue]="p.id">{{ p.fullName }}</option> }
          </select>
        }
        <input type="date" class="field" aria-label="From date" [max]="to() || null" [ngModel]="from()" (ngModelChange)="from.set($event || '')" />
        <input type="date" class="field" aria-label="To date" [min]="from() || null" [ngModel]="to()" (ngModelChange)="to.set($event || '')" />
        <label class="flex items-center gap-2 text-sm text-slate-300">
          <input type="checkbox" class="accent-blue-600" [ngModel]="unreadOnly()" (ngModelChange)="unreadOnly.set($event)" /> Unread only
        </label>
        <button type="button" class="btn-secondary" (click)="readAll()"><i class="fas fa-check-double"></i> Mark all read</button>
      </div>

      <ul class="divide-y divide-line/60">
        @for (n of rows(); track n.id) {
          <li>
            <button type="button" class="flex w-full cursor-pointer items-start gap-3 p-4 text-left transition hover:bg-white/5" (click)="open(n)">
              <span class="mt-1.5 h-2.5 w-2.5 shrink-0 rounded-full" [class]="n.read ? 'bg-transparent' : 'bg-brand-light'" [attr.aria-label]="n.read ? null : 'Unread'"></span>
              <span class="min-w-0 flex-1 text-sm">
                <span class="block" [class]="n.read ? 'text-slate-300' : 'font-semibold text-white'">{{ n.title }}</span>
                @if (n.body) { <span class="block break-words text-slate-400">{{ n.body }}</span> }
                <span class="mt-1 block text-xs text-slate-500">{{ n.section }} · {{ roleLabel(n.actorRole) }} · {{ n.createdAt | bdDate }}</span>
              </span>
              @if (n.link) { <i class="fas fa-chevron-right mt-1 text-xs text-slate-600"></i> }
            </button>
          </li>
        } @empty {
          <li class="p-10 text-center text-slate-500">
            @if (list.isLoading()) { Loading… } @else if (list.error()) { Couldn't load your notifications. } @else { No notifications here. }
          </li>
        }
      </ul>

      @if (result(); as r) {
        @if (r.totalElements > 0) {
          <div class="border-t border-line px-4 py-3">
            <app-pagination [page]="r.page" [size]="r.size" [totalElements]="r.totalElements" [totalPages]="r.totalPages" (pageChange)="page.set($event)" />
          </div>
        }
      }
    </section>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NotificationsFeedTab implements OnInit {
  private readonly api = inject(NotificationApi);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  protected readonly push = inject(PushService);

  readonly changed = output<void>();

  protected readonly canManage = this.auth.hasPermission('NOTIFICATION_MANAGE');
  protected readonly section = signal('');
  protected readonly actorId = signal<number | null>(null);
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly unreadOnly = signal(false);
  protected readonly busy = signal(false);

  private readonly filter = computed<NotificationFilter>(() => ({
    unread: this.unreadOnly(),
    section: this.section(),
    actorId: this.actorId(),
    from: this.from(),
    to: this.to(),
  }));
  protected readonly page = linkedSignal({ source: this.filter, computation: () => 0 });

  protected readonly list = rxResource({
    params: () => ({ filter: this.filter(), page: this.page() }),
    stream: ({ params }) => this.api.list(params.filter, params.page, PAGE_SIZE),
  });
  private readonly catalog = rxResource({ stream: () => this.api.actions() });
  private readonly peopleList = rxResource({ stream: () => (this.canManage ? this.api.people() : of([])) });

  protected readonly result = computed(() => (this.list.hasValue() ? this.list.value() : undefined));
  protected readonly rows = computed(() => this.result()?.content ?? []);
  protected readonly sections = computed(() => [...new Set((this.catalog.hasValue() ? this.catalog.value() : []).map((a) => a.section))]);
  protected readonly people = computed(() => (this.peopleList.hasValue() ? this.peopleList.value() : []));

  ngOnInit(): void {
    void this.push.check();
  }

  protected roleLabel(role: string): string {
    return role === 'ADMIN' ? 'Admin' : role === 'EMPLOYEE' ? 'Employee' : 'Moderator';
  }

  protected async turnOn(): Promise<void> {
    this.busy.set(true);
    try {
      await this.push.enable();
      if (this.push.state() === 'on') {
        this.toast.success('Notifications are on for this device');
      }
    } catch (err) {
      this.toast.error(err instanceof Error ? `Couldn't turn them on: ${err.message}` : errorMessage(err));
    } finally {
      this.busy.set(false);
    }
  }

  protected async turnOff(): Promise<void> {
    await this.push.disable();
    this.toast.success('Notifications are off for this device');
  }

  protected open(item: NotificationItem): void {
    if (!item.read) {
      this.api.markRead(item.id).subscribe(() => {
        this.list.reload();
        this.changed.emit();
      });
    }
    if (item.link) {
      void this.router.navigateByUrl(item.link);
    }
  }

  protected readAll(): void {
    this.api.markAllRead().subscribe({
      next: () => {
        this.list.reload();
        this.changed.emit();
      },
      error: (err: unknown) => this.toast.error(errorMessage(err)),
    });
  }
}
