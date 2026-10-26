import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { CLOSED_MENU, MenuPosition, placeMenu } from '../../../shared/ui/floating-menu';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Params, Router, RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { Role } from '../../../core/auth/auth.models';
import { AuthService } from '../../../core/auth/auth.service';
import { errorMessage } from '../../../core/http/api-error';
import { ToastService } from '../../../core/notify/toast.service';
import { ConfirmService } from '../../../shared/ui/confirm-dialog';
import { Pagination } from '../../../shared/ui/pagination';
import { StaffApi } from '../data/staff.api';
import { PasswordRequest, PermissionInfo, StaffMember } from '../data/staff.models';
import { ResetPasswordDialog } from './reset-password-dialog';
import { BdDatePipe } from '../../../shared/ui/bd-date.pipe';

const PAGE_SIZE = 20;

@Component({
  selector: 'app-staff-list-page',
  imports: [BdDatePipe, RouterLink, Pagination, ResetPasswordDialog],
  templateUrl: './staff-list-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StaffListPage {
  private readonly api = inject(StaffApi);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);
  private readonly auth = inject(AuthService);

  protected readonly openMenuId = signal<number | null>(null);
  /** Where the open row menu sits on screen (fixed, so the table's scroll box can't cut it off). */
  protected readonly menuPosition = signal<MenuPosition>(CLOSED_MENU);
  protected readonly resetting = signal<StaffMember | null>(null);
  protected readonly myId = computed(() => this.auth.user()?.id);

  private readonly params = toSignal(this.route.queryParamMap, { initialValue: this.route.snapshot.queryParamMap });
  protected readonly search = computed(() => this.params().get('q') ?? '');
  protected readonly role = computed(() => (this.params().get('role') as Role | null) ?? '');
  protected readonly status = computed(() => this.params().get('status') ?? '');
  protected readonly page = computed(() => Math.max(0, Number(this.params().get('page') ?? 0) || 0));

  protected readonly staff = rxResource({
    params: () => ({
      q: this.search() || undefined,
      role: this.role() || undefined,
      active: this.status() === 'active' ? true : this.status() === 'inactive' ? false : undefined,
      page: this.page(),
      size: PAGE_SIZE,
    }),
    stream: ({ params }) => this.api.list(params),
  });
  protected readonly catalog = rxResource({ stream: () => this.api.permissionCatalog() });
  protected readonly passwordRequests = rxResource({ stream: () => this.api.passwordRequests() });
  protected readonly openRequests = computed(() => (this.passwordRequests.hasValue() ? this.passwordRequests.value() : []));

  protected readonly result = computed(() => (this.staff.hasValue() ? this.staff.value() : undefined));
  protected readonly rows = computed(() => this.result()?.content ?? []);
  private readonly labels = computed(() => {
    const list: PermissionInfo[] = this.catalog.hasValue() ? this.catalog.value() : [];
    return new Map(list.map((p) => [p.name, p]));
  });

  private timer: ReturnType<typeof setTimeout> | undefined;

  constructor() {
    inject(DestroyRef).onDestroy(() => clearTimeout(this.timer));
  }

  /** "Orders: View, Create · Expenses: View" — grouped, in catalog order. */
  protected permissionSummary(member: StaffMember): { group: string; items: string }[] {
    const groups = new Map<string, string[]>();
    for (const name of member.permissions) {
      const info = this.labels().get(name);
      const group = info?.group ?? 'Other';
      const label = (info?.label ?? name).replace(new RegExp(`\\s*${group}$`, 'i'), '').replace(/^(\w)/, (c) => c.toUpperCase());
      groups.set(group, [...(groups.get(group) ?? []), label]);
    }
    return [...groups].map(([group, items]) => ({ group, items: items.join(', ') }));
  }

  protected onSearch(value: string): void {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.updateParams({ q: value.trim() || null, page: null }), 350);
  }

  protected setFilter(key: 'role' | 'status', value: string): void {
    this.updateParams({ [key]: value || null, page: null });
  }

  protected goToPage(page: number): void {
    this.updateParams({ page: page > 0 ? page : null });
  }

  protected toggleMenu(id: number, event: Event): void {
    event.stopPropagation();
    if (this.openMenuId() === id) {
      this.openMenuId.set(null);
      return;
    }
    this.menuPosition.set(placeMenu(event, () => this.openMenuId.set(null), 230));
    this.openMenuId.set(id);
  }

  protected edit(member: StaffMember): void {
    void this.router.navigate(['/staff', member.id, 'edit']);
  }

  protected async toggleActive(member: StaffMember): Promise<void> {
    this.openMenuId.set(null);
    if (member.active) {
      const confirmed = await this.confirm.ask({
        title: `Deactivate ${member.fullName}?`,
        message: 'They are signed out immediately and cannot sign in until reactivated. Their history is kept.',
        confirmLabel: 'Deactivate',
        danger: true,
      });
      if (!confirmed) {
        return;
      }
    }
    this.run(this.api.setActive(member.id, !member.active),
      member.active ? `${member.fullName} deactivated` : `${member.fullName} reactivated`);
  }

  protected unlock(member: StaffMember): void {
    this.openMenuId.set(null);
    this.run(this.api.unlock(member.id), `${member.fullName} unlocked`);
  }

  protected startReset(member: StaffMember): void {
    this.openMenuId.set(null);
    this.resetting.set(member);
  }

  protected onResetClosed(): void {
    this.resetting.set(null);
    this.staff.reload();
    this.passwordRequests.reload();
  }

  /** Opens the usual "Reset password" dialog for the person who asked. */
  protected resetFor(request: PasswordRequest): void {
    this.api.get(request.userId).subscribe({
      next: (member) => this.resetting.set(member),
      error: (err: unknown) => this.toast.error(errorMessage(err)),
    });
  }

  protected dismiss(request: PasswordRequest): void {
    this.run(this.api.dismissPasswordRequest(request.id), `Request from ${request.fullName} dismissed`);
  }

  private run(request: Observable<unknown>, message: string): void {
    request.subscribe({
      next: () => {
        this.toast.success(message);
        this.staff.reload();
        this.passwordRequests.reload();
      },
      error: (err: unknown) => this.toast.error(errorMessage(err)),
    });
  }

  private updateParams(changes: Params): void {
    void this.router.navigate([], { relativeTo: this.route, queryParams: changes, queryParamsHandling: 'merge' });
  }
}
