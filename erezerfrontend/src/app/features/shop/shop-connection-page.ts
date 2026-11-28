import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, Injectable, OnInit, signal } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { errorMessage } from '../../core/http/api-error';
import { ToastService } from '../../core/notify/toast.service';
import { ConfirmService } from '../../shared/ui/confirm-dialog';
import { BdDatePipe } from '../../shared/ui/bd-date.pipe';

/** Mirrors com.erezer.admin.shop.ShopDtos.ConnectionView. */
export interface ShopConnection {
  connected: boolean;
  keyHint: string | null;
  createdAt: string | null;
}

@Injectable({ providedIn: 'root' })
export class ShopConnectionApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/shop-connection`;

  status(): Observable<ShopConnection> {
    return this.http.get<ShopConnection>(this.base);
  }

  newKey(): Observable<{ key: string }> {
    return this.http.post<{ key: string }>(`${this.base}/key`, null);
  }

  removeKey(): Observable<void> {
    return this.http.delete<void>(`${this.base}/key`);
  }
}

/**
 * "Shop connection": the key the Erezer shop (erezer.com) sends its website orders with. The admin makes a key here
 * and pastes it, with this panel's address, into the shop admin's Settings. Orders the shop sends then appear in
 * Orders, platform Website.
 */
@Component({
  selector: 'app-shop-connection-page',
  imports: [BdDatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mx-auto max-w-3xl">
      <h1 class="page-title">Shop connection</h1>
      <p class="page-subtitle">Let the Erezer shop send its website orders here</p>

      @if (status(); as s) {
        <section class="card mt-6 space-y-5 p-6">
          <div class="flex items-center justify-between gap-4">
            <div>
              <h2 class="font-semibold text-white">Erezer shop</h2>
              <p class="text-sm text-slate-400">
                In the shop admin, staff tick orders and choose <b>Send to Erezer Invoice</b>. They arrive in Orders with
                their photos, platform <b>Website</b>, taken by <b>Erezer website</b>.
              </p>
            </div>
            @if (s.connected) {
              <span class="shrink-0 rounded-full bg-emerald-900/50 px-3 py-1 text-xs font-semibold text-emerald-300"><i class="fas fa-plug mr-1"></i>Connected</span>
            } @else {
              <span class="shrink-0 rounded-full bg-slate-800 px-3 py-1 text-xs font-semibold text-slate-400">Not connected</span>
            }
          </div>

          <label class="block">
            <span class="field-label">This panel's address (paste into the shop)</span>
            <div class="flex gap-2">
              <input class="field font-mono" [value]="panelUrl" readonly />
              <button type="button" class="btn-secondary px-3" (click)="copy(panelUrl)" aria-label="Copy address"><i class="fas fa-copy"></i></button>
            </div>
          </label>

          <div>
            <span class="field-label">Shop key</span>
            @if (newKey(); as key) {
              <div class="flex gap-2">
                <code class="flex-1 rounded border border-emerald-700 bg-emerald-950/40 px-3 py-2 font-mono text-sm text-emerald-100 break-all" data-testid="shop-key">{{ key }}</code>
                <button type="button" class="btn-secondary px-3" (click)="copy(key)" aria-label="Copy key"><i class="fas fa-copy"></i></button>
              </div>
              <span class="mt-1 block text-xs text-amber-300">Copy it now and paste it into the shop's Settings: it can't be shown again.</span>
            } @else if (s.connected) {
              <p class="text-sm text-slate-300">Key ending <span class="font-mono">{{ s.keyHint }}</span>, made {{ s.createdAt | bdDate: 'd MMM y, h:mm a' }}.</p>
            } @else {
              <p class="text-sm text-slate-500">No key yet: the shop can't send orders.</p>
            }
            <div class="mt-3 flex flex-wrap gap-2">
              <button type="button" class="btn-primary" (click)="makeKey(s.connected)" data-testid="shop-new-key">
                <i class="fas fa-key"></i> {{ s.connected ? 'Make a new key' : 'Make a key' }}
              </button>
              @if (s.connected) {
                <button type="button" class="btn-secondary" (click)="disconnect()"><i class="fas fa-unlink"></i> Disconnect</button>
              }
            </div>
          </div>
          <p class="text-xs text-slate-500"><i class="fas fa-lock mr-1"></i>Whoever has the key can add orders here. Only admins can see this page.</p>
        </section>
      }
    </div>
  `,
})
export class ShopConnectionPage implements OnInit {
  private readonly api = inject(ShopConnectionApi);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);

  protected readonly status = signal<ShopConnection | null>(null);
  protected readonly newKey = signal<string | null>(null);
  protected readonly panelUrl = window.location.origin;

  ngOnInit(): void {
    this.load();
  }

  protected async makeKey(replacing: boolean): Promise<void> {
    if (replacing) {
      const ok = await this.confirm.ask({
        title: 'Make a new key?',
        message: 'The old key stops working at once: the shop can\'t send orders until you paste the new key into its Settings.',
        confirmLabel: 'Make new key',
        danger: true,
      });
      if (!ok) {
        return;
      }
    }
    this.api.newKey().subscribe({
      next: (r) => {
        this.newKey.set(r.key);
        this.load();
      },
      error: (err: unknown) => this.toast.error(errorMessage(err)),
    });
  }

  protected async disconnect(): Promise<void> {
    const ok = await this.confirm.ask({
      title: 'Disconnect the shop?',
      message: 'The shop\'s key stops working at once and it can\'t send orders until you make a new key.',
      confirmLabel: 'Disconnect',
      danger: true,
    });
    if (!ok) {
      return;
    }
    this.api.removeKey().subscribe({
      next: () => {
        this.newKey.set(null);
        this.load();
        this.toast.success('Shop disconnected');
      },
      error: (err: unknown) => this.toast.error(errorMessage(err)),
    });
  }

  protected copy(text: string): void {
    void navigator.clipboard?.writeText(text).then(() => this.toast.info('Copied'));
  }

  private load(): void {
    this.api.status().subscribe({
      next: (s) => this.status.set(s),
      error: (err: unknown) => this.toast.error(errorMessage(err)),
    });
  }
}
