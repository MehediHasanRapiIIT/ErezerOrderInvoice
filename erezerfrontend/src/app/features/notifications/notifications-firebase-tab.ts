import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { errorMessage } from '../../core/http/api-error';
import { ToastService } from '../../core/notify/toast.service';
import { FirebaseSettings, NotificationApi } from './data/notification.api';

/** The Firebase connection. It is stored encrypted in the database, so it never goes in the repository. */
@Component({
  selector: 'app-notifications-firebase-tab',
  imports: [FormsModule],
  template: `
    @if (settings(); as s) {
      <div class="card mb-4 p-4 text-sm">
        @if (s.configured) {
          <p class="text-emerald-300"><i class="fas fa-check-circle mr-2"></i>Connected to Firebase project <b>{{ s.projectId }}</b>.</p>
          <p class="mt-1 break-all text-slate-400">{{ s.clientEmail }} · web key {{ s.apiKey }}</p>
        } @else {
          <p class="text-amber-300"><i class="fas fa-exclamation-circle mr-2"></i>Not connected yet. Notifications still appear in the list, without pushes.</p>
        }
      </div>
    }

    <section class="card p-5">
      <h2 class="font-semibold text-white">Firebase setup</h2>
      <ol class="mt-2 list-decimal space-y-1 pl-5 text-sm text-slate-400">
        <li>In the Firebase console, create a project and add a <b class="text-slate-200">Web app</b>.</li>
        <li>Copy its <b class="text-slate-200">firebaseConfig</b> into the first box.</li>
        <li>Project settings → Cloud Messaging → Web Push certificates: <b class="text-slate-200">Generate key pair</b>, and copy it into the second box.</li>
        <li>Project settings → Service accounts: <b class="text-slate-200">Generate new private key</b>, and choose that file below.</li>
      </ol>

      <label class="field-label mt-4 block" for="fb-web">Web app config *</label>
      <textarea id="fb-web" rows="7" class="field font-mono text-xs" maxlength="3000" placeholder="const firebaseConfig = { apiKey: '…', projectId: '…', … };" [(ngModel)]="webConfig"></textarea>

      <label class="field-label mt-3 block" for="fb-vapid">Web Push key *</label>
      <input id="fb-vapid" class="field font-mono text-xs" maxlength="200" [(ngModel)]="vapidKey" />

      <p class="field-label mt-3">Service account key file{{ settings()?.configured ? '' : ' *' }}</p>
      <label class="flex cursor-pointer items-center gap-3 rounded-lg border border-dashed border-line p-3 text-sm text-slate-400 hover:text-white">
        <i class="fas fa-file-upload"></i>
        {{ keyName() || (settings()?.configured ? 'Saved. Choose a file only to replace it.' : 'Choose the .json file') }}
        <input type="file" class="sr-only" accept="application/json,.json" (change)="onKey($any($event.target))" />
      </label>
      <p class="mt-1 text-xs text-slate-500">It is encrypted before it is stored, and it is never shown again.</p>

      <div class="mt-5 flex flex-wrap justify-end gap-2">
        @if (settings()?.configured) {
          <button type="button" class="btn-secondary" [disabled]="testing()" (click)="test()"><i class="fas fa-paper-plane"></i> Send test to my devices</button>
        }
        <button type="button" class="btn-primary" [disabled]="saving() || !webConfig.trim() || !vapidKey.trim()" (click)="save()">Save</button>
      </div>
    </section>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NotificationsFirebaseTab {
  private readonly api = inject(NotificationApi);
  private readonly toast = inject(ToastService);

  protected readonly settings = signal<FirebaseSettings | null>(null);
  protected readonly keyName = signal('');
  protected readonly saving = signal(false);
  protected readonly testing = signal(false);
  protected webConfig = '';
  protected vapidKey = '';
  private serviceAccount = '';

  constructor() {
    this.api.settings().subscribe({
      next: (settings) => {
        this.settings.set(settings);
        this.vapidKey = settings.vapidKey ?? '';
      },
      error: (err: unknown) => this.toast.error(errorMessage(err)),
    });
  }

  protected async onKey(element: HTMLInputElement): Promise<void> {
    const file = element.files?.[0];
    element.value = '';
    if (file) {
      this.serviceAccount = await file.text();
      this.keyName.set(file.name);
    }
  }

  protected save(): void {
    this.saving.set(true);
    this.api.saveSettings({ serviceAccount: this.serviceAccount, webConfig: this.webConfig, vapidKey: this.vapidKey }).subscribe({
      next: (settings) => {
        this.settings.set(settings);
        this.serviceAccount = '';
        this.keyName.set('');
        this.toast.success('Firebase connected');
        this.saving.set(false);
      },
      error: (err: unknown) => {
        this.toast.error(errorMessage(err));
        this.saving.set(false);
      },
    });
  }

  protected test(): void {
    this.testing.set(true);
    this.api.test().subscribe({
      next: ({ sent, failed }) => {
        this.testing.set(false);
        if (sent + failed === 0) {
          this.toast.info('You have no devices yet. Enable notifications on the Notifications tab first.');
        } else if (failed > 0) {
          this.toast.error(`${sent} sent, ${failed} failed. Check the Firebase details.`);
        } else {
          this.toast.success(`Sent to ${sent} device${sent === 1 ? '' : 's'}`);
        }
      },
      error: (err: unknown) => {
        this.toast.error(errorMessage(err));
        this.testing.set(false);
      },
    });
  }
}
