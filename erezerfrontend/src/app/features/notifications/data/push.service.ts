import { inject, Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { NotificationApi } from './notification.api';

export type PushState = 'loading' | 'unsupported' | 'install-needed' | 'not-configured' | 'blocked' | 'off' | 'on';

const TOKEN_KEY = 'erezer.push-token';
const WORKER = '/firebase-messaging-sw.js';

/** Turns push notifications on or off for this phone or browser. Firebase is only loaded when someone turns them on. */
@Injectable({ providedIn: 'root' })
export class PushService {
  private readonly api = inject(NotificationApi);

  readonly state = signal<PushState>('loading');

  async check(): Promise<void> {
    this.state.set(await this.findState());
  }

  private async findState(): Promise<PushState> {
    if (!('serviceWorker' in navigator) || !('PushManager' in window) || !('Notification' in window)) {
      return isIphone() && !isInstalled() ? 'install-needed' : 'unsupported';
    }
    const config = await firstValueFrom(this.api.pushConfig()).catch(() => null);
    if (!config?.configured) return 'not-configured';
    if (Notification.permission === 'denied') return 'blocked';
    return Notification.permission === 'granted' && storedToken() ? 'on' : 'off';
  }

  /** @throws when the browser or Firebase refuses */
  async enable(): Promise<void> {
    const config = await firstValueFrom(this.api.pushConfig());
    if (await Notification.requestPermission() !== 'granted') {
      this.state.set('blocked');
      return;
    }
    const registration = await navigator.serviceWorker.register(WORKER);
    await navigator.serviceWorker.ready;
    const [{ initializeApp, getApps }, { getMessaging, getToken }] = await Promise.all([import('firebase/app'), import('firebase/messaging')]);
    const app = getApps()[0] ?? initializeApp(config.firebase);
    const token = await getToken(getMessaging(app), { vapidKey: config.vapidKey, serviceWorkerRegistration: registration });
    await firstValueFrom(this.api.addDevice(token));
    remember(token);
    this.state.set('on');
  }

  /** Stops pushes here; used by the button and when signing out. */
  async disable(): Promise<void> {
    const token = storedToken();
    if (token) {
      await firstValueFrom(this.api.removeDevice(token)).catch(() => undefined);
      remember(null);
    }
    if (this.state() === 'on') {
      this.state.set('off');
    }
  }
}

function isIphone(): boolean {
  return /iPhone|iPad|iPod/.test(navigator.userAgent);
}

function isInstalled(): boolean {
  return window.matchMedia('(display-mode: standalone)').matches;
}

function storedToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

function remember(token: string | null): void {
  try {
    if (token) {
      localStorage.setItem(TOKEN_KEY, token);
    } else {
      localStorage.removeItem(TOKEN_KEY);
    }
  } catch {
    // Private windows may refuse storage; pushes still work until the page is closed.
  }
}
