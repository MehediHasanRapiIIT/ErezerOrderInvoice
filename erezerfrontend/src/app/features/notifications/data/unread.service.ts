import { inject, Injectable, signal } from '@angular/core';
import { catchError, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { NotificationApi } from './notification.api';

/** The number on the bell. */
@Injectable({ providedIn: 'root' })
export class UnreadService {
  private readonly api = inject(NotificationApi);
  private readonly auth = inject(AuthService);

  readonly count = signal(0);

  refresh(): void {
    if (!this.auth.hasPermission('NOTIFICATION_VIEW')) {
      this.count.set(0);
      return;
    }
    this.api
      .unread()
      .pipe(catchError(() => of({ count: 0 })))
      .subscribe(({ count }) => this.count.set(count));
  }
}
