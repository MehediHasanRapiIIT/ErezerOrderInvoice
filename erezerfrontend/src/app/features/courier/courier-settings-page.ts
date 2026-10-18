import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule, NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { errorMessage } from '../../core/http/api-error';
import { ToastService } from '../../core/notify/toast.service';
import { ConfirmService } from '../../shared/ui/confirm-dialog';
import { TakaPipe } from '../../shared/ui/taka.pipe';
import { CourierApi, CourierSettings, DELIVERY_TYPES } from './courier.api';
import { BdDatePipe } from '../../shared/ui/bd-date.pipe';

@Component({
  selector: 'app-courier-settings-page',
  imports: [BdDatePipe, FormsModule, ReactiveFormsModule, TakaPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mx-auto max-w-3xl">
      <h1 class="page-title">Courier settings</h1>
      <p class="page-subtitle">Connect Steadfast so orders can be sent to the courier from the panel</p>

      @if (settings(); as s) {
        <form [formGroup]="form" (ngSubmit)="save()" class="card mt-6 space-y-5 p-6" novalidate>
          <div class="flex items-center justify-between gap-4">
            <div>
              <h2 class="font-semibold text-white">Steadfast Courier</h2>
              <p class="text-sm text-slate-400">API keys are in your Steadfast merchant panel (API page) or from Steadfast support.</p>
            </div>
            <label class="flex cursor-pointer items-center gap-2 text-sm text-slate-200">
              <input type="checkbox" formControlName="enabled" class="h-4 w-4 accent-blue-600" /> Enabled
            </label>
          </div>

          <label class="block">
            <span class="field-label">API URL</span>
            <input formControlName="baseUrl" class="field font-mono" />
            <span class="mt-1 block text-xs text-slate-500">Default: https://portal.packzy.com/api/v1</span>
          </label>

          <div class="grid grid-cols-1 gap-5 sm:grid-cols-2">
            <label class="block">
              <span class="field-label">API key</span>
              <input formControlName="apiKey" class="field font-mono" autocomplete="off" [placeholder]="s.apiKeyHint ? s.apiKeyHint + ' (saved; leave empty to keep)' : 'Paste your Api-Key'" />
            </label>
            <label class="block">
              <span class="field-label">Secret key</span>
              <input formControlName="secretKey" type="password" class="field font-mono" autocomplete="new-password" [placeholder]="s.secretKeySet ? '•••••••• (saved; leave empty to keep)' : 'Paste your Secret-Key'" />
            </label>
          </div>
          <p class="text-xs text-slate-500"><i class="fas fa-lock mr-1"></i>Keys are stored encrypted and are never shown again after saving.</p>

          <label class="block sm:w-1/2">
            <span class="field-label">Default delivery type</span>
            <select formControlName="defaultDeliveryType" class="field">
              @for (t of deliveryTypes; track t.value) {
                <option [ngValue]="t.value">{{ t.label }}</option>
              }
            </select>
          </label>

          <div class="flex flex-wrap items-center justify-between gap-3 border-t border-line pt-5">
            <div class="text-sm">
              @if (balance() !== null) {
                <span class="text-emerald-300"><i class="fas fa-check-circle mr-1"></i>Connected · balance {{ balance() | taka }}</span>
              }
            </div>
            <div class="flex gap-2">
              <button type="button" class="btn-secondary" (click)="test()" [disabled]="testing() || !s.secretKeySet">
                @if (testing()) { <i class="fas fa-circle-notch fa-spin"></i> } Test connection
              </button>
              <button type="submit" class="btn-primary" [disabled]="saving()">Save</button>
            </div>
          </div>
        </form>

        <section class="card mt-6 space-y-4 p-6">
          <h2 class="font-semibold text-white">Status updates (webhook)</h2>
          <p class="text-sm text-slate-400">
            Paste these into the <b>Webhook</b> page of your Steadfast panel so deliveries and returns update orders instantly.
            Every update is checked against the token and Steadfast's <code>X-Signature</code>, so forged calls are rejected.
            Without a webhook the panel checks Steadfast every 10 minutes instead.
          </p>
          <label class="block">
            <span class="field-label">Callback URL</span>
            <div class="flex gap-2">
              <input class="field font-mono" [value]="webhookUrl" readonly />
              <button type="button" class="btn-secondary px-3" (click)="copy(webhookUrl)" aria-label="Copy URL"><i class="fas fa-copy"></i></button>
            </div>
            @if (isLocal) {
              <span class="mt-1 block text-xs text-amber-300"><i class="fas fa-exclamation-triangle mr-1"></i>This address is only reachable from this computer. Steadfast needs your public https domain.</span>
            }
          </label>
          <div>
            <span class="field-label">Auth token</span>
            @if (newToken(); as token) {
              <div class="flex gap-2">
                <code class="flex-1 rounded border border-emerald-700 bg-emerald-950/40 px-3 py-2 font-mono text-sm text-emerald-100 break-all">{{ token }}</code>
                <button type="button" class="btn-secondary px-3" (click)="copy(token)" aria-label="Copy token"><i class="fas fa-copy"></i></button>
              </div>
              <span class="mt-1 block text-xs text-amber-300">Copy it now: it is stored encrypted and can't be shown again.</span>
            } @else {
              <p class="text-sm" [class]="s.webhookTokenSet ? 'text-slate-300' : 'text-slate-500'">
                {{ s.webhookTokenSet ? 'A token is set.' : 'No token yet.' }}
              </p>
            }
            <div class="mt-2 flex flex-wrap gap-2">
              <button type="button" class="btn-secondary" (click)="regenerate(s.webhookTokenSet)">
                <i class="fas fa-key"></i> {{ s.webhookTokenSet ? 'Generate a new token' : 'Generate token' }}
              </button>
              <button type="button" class="btn-secondary" (click)="pasting.set(!pasting())" [attr.aria-expanded]="pasting()">
                <i class="fas fa-paste"></i> Use a token from Steadfast
              </button>
            </div>
            @if (pasting()) {
              <div class="mt-3 flex flex-col gap-2 sm:flex-row">
                <input class="field font-mono" [(ngModel)]="pastedToken" autocomplete="off" placeholder="Token generated in Steadfast's Webhook page" aria-label="Steadfast webhook token" />
                <button type="button" class="btn-primary" (click)="savePasted()" [disabled]="!validPasted()">Save token</button>
              </div>
              <span class="mt-1 block text-xs text-slate-500">16-200 characters: letters, digits and - _ . =</span>
            }
          </div>
        </section>
        <p class="mt-3 text-xs text-slate-500">Last saved {{ s.updatedAt | bdDate: 'd MMM y, h:mm a' }}</p>
      } @else {
        <div class="flex justify-center py-24 text-slate-400"><i class="fas fa-circle-notch fa-spin text-2xl"></i></div>
      }
    </div>
  `,
})
export class CourierSettingsPage implements OnInit {
  private readonly api = inject(CourierApi);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);

  protected readonly deliveryTypes = DELIVERY_TYPES;
  protected readonly settings = signal<CourierSettings | null>(null);
  protected readonly saving = signal(false);
  protected readonly testing = signal(false);
  protected readonly balance = signal<number | null>(null);
  protected readonly newToken = signal<string | null>(null);
  protected readonly pasting = signal(false);
  protected pastedToken = '';
  protected readonly webhookUrl = `${window.location.origin}/api/webhooks/steadfast`;
  protected readonly isLocal = /localhost|127\.0\.0\.1/.test(window.location.hostname);

  protected readonly form = inject(NonNullableFormBuilder).group({
    enabled: [false],
    baseUrl: ['https://portal.packzy.com/api/v1', Validators.required],
    apiKey: [''],
    secretKey: [''],
    defaultDeliveryType: [0],
  });

  ngOnInit(): void {
    this.api.settings().subscribe({
      next: (s) => this.apply(s),
      error: (err: unknown) => this.toast.error(errorMessage(err)),
    });
  }

  protected save(): void {
    const v = this.form.getRawValue();
    this.saving.set(true);
    this.api
      .saveSettings({
        enabled: v.enabled,
        baseUrl: v.baseUrl.trim(),
        defaultDeliveryType: v.defaultDeliveryType,
        apiKey: v.apiKey.trim() || null,
        secretKey: v.secretKey.trim() || null,
      })
      .subscribe({
        next: (s) => {
          this.apply(s);
          this.saving.set(false);
          this.toast.success('Courier settings saved');
        },
        error: (err: unknown) => {
          this.saving.set(false);
          this.toast.error(errorMessage(err));
        },
      });
  }

  protected test(): void {
    this.testing.set(true);
    this.balance.set(null);
    this.api.testConnection().subscribe({
      next: (r) => {
        this.balance.set(r.balance);
        this.testing.set(false);
      },
      error: (err: unknown) => {
        this.testing.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected async regenerate(replacing: boolean): Promise<void> {
    if (replacing) {
      const ok = await this.confirm.ask({
        title: 'Replace the webhook token?',
        message: 'Steadfast updates stop arriving until you paste the new token into the Steadfast panel.',
        confirmLabel: 'Replace token',
        danger: true,
      });
      if (!ok) {
        return;
      }
    }
    this.api.regenerateWebhookToken().subscribe({
      next: (r) => {
        this.newToken.set(r.token);
        const s = this.settings();
        if (s) {
          this.settings.set({ ...s, webhookTokenSet: true });
        }
      },
      error: (err: unknown) => this.toast.error(errorMessage(err)),
    });
  }

  protected validPasted(): boolean {
    return /^[A-Za-z0-9._=-]{16,200}$/.test(this.pastedToken.trim());
  }

  protected async savePasted(): Promise<void> {
    const s = this.settings();
    if (s?.webhookTokenSet) {
      const ok = await this.confirm.ask({
        title: 'Replace the webhook token?',
        message: 'Updates signed with the old token will be rejected from now on.',
        confirmLabel: 'Replace token',
        danger: true,
      });
      if (!ok) {
        return;
      }
    }
    this.api.setWebhookToken(this.pastedToken.trim()).subscribe({
      next: () => {
        this.pastedToken = '';
        this.pasting.set(false);
        this.newToken.set(null);
        const current = this.settings();
        if (current) {
          this.settings.set({ ...current, webhookTokenSet: true });
        }
        this.toast.success('Webhook token saved');
      },
      error: (err: unknown) => this.toast.error(errorMessage(err)),
    });
  }

  protected copy(text: string): void {
    void navigator.clipboard?.writeText(text).then(() => this.toast.info('Copied'));
  }

  private apply(s: CourierSettings): void {
    this.settings.set(s);
    this.form.reset({ enabled: s.enabled, baseUrl: s.baseUrl, apiKey: '', secretKey: '', defaultDeliveryType: s.defaultDeliveryType });
  }
}
