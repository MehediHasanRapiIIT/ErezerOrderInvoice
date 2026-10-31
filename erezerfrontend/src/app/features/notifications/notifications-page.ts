import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { AuthService } from '../../core/auth/auth.service';
import { UnreadService } from './data/unread.service';
import { NotificationsFeedTab } from './notifications-feed-tab';
import { NotificationsFirebaseTab } from './notifications-firebase-tab';
import { NotificationsPeopleTab } from './notifications-people-tab';
import { NotificationsRulesTab } from './notifications-rules-tab';

type Tab = 'feed' | 'rules' | 'people' | 'firebase';

/** Notifications, and everything about them that an admin can set. */
@Component({
  selector: 'app-notifications-page',
  imports: [NotificationsFeedTab, NotificationsFirebaseTab, NotificationsPeopleTab, NotificationsRulesTab],
  template: `
    <h1 class="page-title">Notifications</h1>
    <p class="page-subtitle">{{ canManage ? 'What people did, and who hears about it.' : 'What people did.' }}</p>

    @if (canManage) {
      <div class="mt-6 flex flex-wrap gap-2" role="tablist">
        @for (t of tabs; track t.value) {
          <button type="button" role="tab" [attr.aria-selected]="tab() === t.value" [class]="tab() === t.value ? 'btn-primary' : 'btn-secondary'" (click)="tab.set(t.value)">
            <i class="fas" [class]="t.icon"></i> {{ t.label }}
          </button>
        }
      </div>
    }

    <div class="mt-4">
      @switch (tab()) {
        @case ('feed') { <app-notifications-feed-tab (changed)="unread.refresh()" /> }
        @case ('rules') { <app-notifications-rules-tab /> }
        @case ('people') { <app-notifications-people-tab /> }
        @case ('firebase') { <app-notifications-firebase-tab /> }
      }
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NotificationsPage {
  protected readonly unread = inject(UnreadService);
  protected readonly canManage = inject(AuthService).hasPermission('NOTIFICATION_MANAGE');
  protected readonly tab = signal<Tab>('feed');
  protected readonly tabs: readonly { value: Tab; label: string; icon: string }[] = [
    { value: 'feed', label: 'Notifications', icon: 'fa-bell' },
    { value: 'rules', label: 'Actions', icon: 'fa-sliders-h' },
    { value: 'people', label: 'People', icon: 'fa-user-check' },
    { value: 'firebase', label: 'Firebase', icon: 'fa-plug' },
  ];
}
