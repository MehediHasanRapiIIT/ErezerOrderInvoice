import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, filter, of } from 'rxjs';
import { StaffApi } from '../../features/staff/data/staff.api';
import { AuthService } from '../../core/auth/auth.service';
import { NAV_ITEMS } from '../nav-items';

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './shell.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Shell {
  protected readonly auth = inject(AuthService);
  protected readonly sidebarOpen = signal(false);
  protected readonly userMenuOpen = signal(false);

  private readonly staffApi = inject(StaffApi);
  /** "Forgot password" requests waiting for an admin, shown on the Staff Management item. */
  protected readonly passwordRequests = signal(0);

  protected readonly navItems = computed(() =>
    NAV_ITEMS.filter((item) => this.auth.hasAnyPermission(item.permissions)),
  );

  protected readonly initials = computed(() =>
    (this.auth.user()?.fullName ?? '?')
      .split(/\s+/)
      .slice(0, 2)
      .map((part) => part.charAt(0).toUpperCase())
      .join(''),
  );

  constructor() {
    // Close the mobile drawer and user menu after navigating.
    inject(Router)
      .events.pipe(
        filter((e) => e instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe(() => {
        this.sidebarOpen.set(false);
        this.userMenuOpen.set(false);
        this.refreshPasswordRequests();
      });
    this.refreshPasswordRequests();
  }

  private refreshPasswordRequests(): void {
    if (!this.auth.hasPermission('STAFF_MANAGE')) return;
    this.staffApi
      .passwordRequests()
      .pipe(catchError(() => of([])))
      .subscribe((list) => this.passwordRequests.set(list.length));
  }

  protected logout(): void {
    this.auth.logout();
  }
}
