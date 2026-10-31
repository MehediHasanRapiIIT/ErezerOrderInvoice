import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Params, Router, RouterLink } from '@angular/router';
import { errorMessage } from '../../core/http/api-error';
import { ToastService } from '../../core/notify/toast.service';
import { HasPermissionDirective } from '../../shared/has-permission.directive';
import { ConfirmService } from '../../shared/ui/confirm-dialog';
import { Pagination } from '../../shared/ui/pagination';
import { EmployeeApi } from './data/employee.api';
import { EmployeeSummary } from './data/employee.models';

const PAGE_SIZE = 20;
const DEBOUNCE_MS = 350;

/** The people the shop employs. Search and page live in the URL so reloads keep them. */
@Component({
  selector: 'app-employee-list-page',
  imports: [HasPermissionDirective, Pagination, RouterLink],
  template: `
    <div class="flex flex-wrap items-center justify-between gap-4">
      <div>
        <h1 class="page-title">Employees</h1>
        <p class="page-subtitle">Everyone the shop employs, with their documents and salary accounts.</p>
      </div>
      <a *appHasPermission="'EMPLOYEE_CREATE'" routerLink="/employees/new" class="btn-primary">
        <i class="fas fa-user-plus"></i> Register Employee
      </a>
    </div>

    <section class="card mt-6 overflow-hidden">
      <div class="border-b border-line p-4">
        <div class="relative">
          <i class="fas fa-search pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-sm text-slate-500"></i>
          <input type="search" class="field pl-9" placeholder="Search name, Employee ID, contact number or NID…" [value]="search()" (input)="debounced($any($event.target).value)" aria-label="Search employees" />
        </div>
      </div>

      <div class="overflow-x-auto">
        <table class="phone-cards w-full min-w-[820px] text-left text-sm">
          <thead class="border-b border-line text-slate-400">
            <tr>
              <th class="px-4 py-3 font-semibold">Name</th>
              <th class="px-4 py-3 font-semibold">Employee ID</th>
              <th class="px-4 py-3 font-semibold">Contact</th>
              <th class="px-4 py-3 font-semibold">NID</th>
              <th class="px-4 py-3 font-semibold">Present address</th>
              <th class="px-4 py-3"><span class="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            @for (e of rows(); track e.id) {
              <tr class="border-b border-line/60">
                <td class="py-3 pl-4 pr-32 md:pr-4">
                  <a [routerLink]="['/employees', e.id]" class="flex items-center gap-3 font-medium text-white hover:underline">
                    <img [src]="e.photoUrl" alt="" class="h-10 w-10 shrink-0 rounded-full object-cover" loading="lazy" />
                    {{ e.fullName }}
                  </a>
                </td>
                <td class="cell-inline whitespace-nowrap px-4 py-3 text-slate-200" data-label="Employee ID">{{ e.employeeCode }}</td>
                <td class="cell-inline whitespace-nowrap px-4 py-3 text-slate-200" data-label="Contact"><a [href]="'tel:' + e.phone" class="hover:underline">{{ e.phone }}</a></td>
                <td class="cell-inline whitespace-nowrap px-4 py-3 text-slate-200" data-label="NID">{{ e.nidNumber }}</td>
                <td class="cell-inline px-4 py-3 text-slate-300" data-label="Address">{{ e.presentAddress }}</td>
                <td class="cell-actions px-4 py-3 text-right">
                  <div class="flex justify-end gap-2">
                    <a [routerLink]="['/employees', e.id]" class="btn-secondary hidden md:inline-flex">View</a>
                    <a *appHasPermission="'EMPLOYEE_EDIT'" [routerLink]="['/employees', e.id, 'edit']" class="btn-secondary">Edit</a>
                    <button *appHasPermission="'EMPLOYEE_DELETE'" type="button" class="btn-secondary" [attr.aria-label]="'Delete ' + e.fullName" (click)="remove(e)"><i class="fas fa-trash"></i></button>
                  </div>
                </td>
              </tr>
            } @empty {
              <tr><td colspan="6" class="px-4 py-10 text-center text-slate-500">
                @if (employees.isLoading()) { Loading… } @else if (employees.error()) { Couldn't load the employees. } @else if (search()) { Nobody matches this search. } @else { No employees registered yet. }
              </td></tr>
            }
          </tbody>
        </table>
      </div>

      @if (result(); as r) {
        @if (r.totalElements > 0) {
          <div class="border-t border-line px-4 py-3">
            <app-pagination [page]="r.page" [size]="r.size" [totalElements]="r.totalElements" [totalPages]="r.totalPages" (pageChange)="goToPage($event)" />
          </div>
        }
      }
    </section>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EmployeeListPage {
  private readonly api = inject(EmployeeApi);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);

  private readonly params = toSignal(this.route.queryParamMap, { initialValue: this.route.snapshot.queryParamMap });
  protected readonly search = computed(() => this.params().get('q') ?? '');
  private readonly page = computed(() => Math.max(0, Number(this.params().get('page') ?? 0) || 0));

  protected readonly employees = rxResource({
    params: () => ({ q: this.search(), page: this.page() }),
    stream: ({ params }) => this.api.list(params.q, params.page, PAGE_SIZE),
  });
  protected readonly result = computed(() => (this.employees.hasValue() ? this.employees.value() : undefined));
  protected readonly rows = computed(() => this.result()?.content ?? []);

  private timer: ReturnType<typeof setTimeout> | undefined;

  constructor() {
    inject(DestroyRef).onDestroy(() => clearTimeout(this.timer));
  }

  protected debounced(value: string): void {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.updateParams({ q: value.trim() || null, page: null }), DEBOUNCE_MS);
  }

  protected goToPage(page: number): void {
    this.updateParams({ page: page || null });
  }

  private updateParams(params: Params): void {
    void this.router.navigate([], { relativeTo: this.route, queryParams: params, queryParamsHandling: 'merge' });
  }

  protected async remove(employee: EmployeeSummary): Promise<void> {
    const confirmed = await this.confirm.ask({
      title: `Delete ${employee.fullName}?`,
      message: 'Their record, NID image and ID card image will be removed permanently.',
      danger: true,
    });
    if (!confirmed) return;
    this.api.delete(employee.id).subscribe({
      next: () => {
        this.toast.success('Employee deleted');
        const r = this.result();
        if (r && r.page > 0 && r.content.length === 1) {
          this.goToPage(r.page - 1);
        } else {
          this.employees.reload();
        }
      },
      error: (err: unknown) => this.toast.error(errorMessage(err)),
    });
  }
}
