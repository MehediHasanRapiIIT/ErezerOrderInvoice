import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { errorMessage } from '../../core/http/api-error';
import { ToastService } from '../../core/notify/toast.service';
import { HasPermissionDirective } from '../../shared/has-permission.directive';
import { ConfirmService } from '../../shared/ui/confirm-dialog';
import { EmployeeApi } from './data/employee.api';
import { ACCOUNT_TYPE_LABELS } from './data/employee.models';

/** Everything held about one employee. */
@Component({
  selector: 'app-employee-detail-page',
  imports: [HasPermissionDirective, RouterLink],
  template: `
    <div class="mx-auto max-w-3xl">
      <a routerLink="/employees" class="mb-2 inline-flex items-center gap-1 text-sm text-slate-400 hover:text-white">
        <i class="fas fa-arrow-left text-xs"></i> Employees
      </a>

      @if (employee(); as e) {
        <div class="flex flex-wrap items-center justify-between gap-4">
          <div class="flex items-center gap-4">
            <a [href]="e.photo.url" target="_blank" rel="noopener" class="shrink-0">
              <img [src]="e.photo.url" alt="Photo of {{ e.fullName }}" class="h-20 w-20 rounded-full object-cover" />
            </a>
            <div>
              <h1 class="page-title">{{ e.fullName }}</h1>
              <p class="page-subtitle">Employee ID {{ e.employeeCode }}</p>
            </div>
          </div>
          <div class="flex gap-2">
            <a *appHasPermission="'EMPLOYEE_EDIT'" [routerLink]="['/employees', e.id, 'edit']" class="btn-secondary"><i class="fas fa-pen"></i> Edit</a>
            <button *appHasPermission="'EMPLOYEE_DELETE'" type="button" class="btn-secondary" (click)="remove()"><i class="fas fa-trash"></i> Delete</button>
          </div>
        </div>

        <section class="card mt-6 p-5">
          <h2 class="font-semibold text-white">Contact</h2>
          <dl class="mt-3 grid gap-4 text-sm sm:grid-cols-2">
            <div><dt class="text-slate-400">Contact number</dt><dd class="text-slate-100"><a [href]="'tel:' + e.phone" class="hover:underline">{{ e.phone }}</a></dd></div>
            <div><dt class="text-slate-400">NID number</dt><dd class="text-slate-100">{{ e.nidNumber }}</dd></div>
            <div class="sm:col-span-2"><dt class="text-slate-400">Present address</dt><dd class="whitespace-pre-line text-slate-100">{{ e.presentAddress }}</dd></div>
            <div class="sm:col-span-2"><dt class="text-slate-400">Permanent address</dt><dd class="whitespace-pre-line text-slate-100">{{ e.permanentAddress || '–' }}</dd></div>
          </dl>
        </section>

        <section class="card mt-4 p-5">
          <h2 class="font-semibold text-white">Family</h2>
          <dl class="mt-3 grid gap-4 text-sm sm:grid-cols-2">
            <div><dt class="text-slate-400">Father's name</dt><dd class="text-slate-100">{{ e.fatherName || '–' }}</dd></div>
            <div><dt class="text-slate-400">Father's contact number</dt><dd class="text-slate-100">{{ e.fatherPhone || '–' }}</dd></div>
            <div><dt class="text-slate-400">Mother's name</dt><dd class="text-slate-100">{{ e.motherName || '–' }}</dd></div>
            <div><dt class="text-slate-400">Mother's contact number</dt><dd class="text-slate-100">{{ e.motherPhone || '–' }}</dd></div>
          </dl>
        </section>

        <section class="card mt-4 p-5">
          <h2 class="font-semibold text-white">Documents</h2>
          <div class="mt-3 grid gap-4 sm:grid-cols-2">
            <div>
              <p class="mb-2 text-sm text-slate-400">NID image</p>
              <a [href]="e.nidImage.url" target="_blank" rel="noopener"><img [src]="e.nidImage.url" alt="NID of {{ e.fullName }}" class="max-h-56 w-full rounded-lg object-contain" /></a>
            </div>
            <div>
              <p class="mb-2 text-sm text-slate-400">Employee ID card</p>
              @if (e.idCardImage; as card) {
                <a [href]="card.url" target="_blank" rel="noopener"><img [src]="card.url" alt="ID card of {{ e.fullName }}" class="max-h-56 w-full rounded-lg object-contain" /></a>
              } @else {
                <p class="text-sm text-slate-500">Not added</p>
              }
            </div>
          </div>
        </section>

        <section class="card mt-4 p-5">
          <h2 class="font-semibold text-white">Salary accounts</h2>
          <div class="mt-3 space-y-3 text-sm">
            @for (a of e.salaryAccounts; track $index) {
              <div class="rounded-lg border border-line p-3">
                <p class="font-medium text-white">{{ a.provider || labels[a.type] }}</p>
                <p class="text-slate-200">{{ a.accountNumber }}</p>
                @if (a.accountName) { <p class="text-slate-400">{{ a.accountName }}</p> }
                @if (a.branch) { <p class="text-slate-400">Branch: {{ a.branch }}</p> }
                @if (a.routingNumber) { <p class="text-slate-400">Routing number: {{ a.routingNumber }}</p> }
              </div>
            } @empty {
              <p class="text-slate-500">None added</p>
            }
          </div>
        </section>
      } @else if (data.error()) {
        <p class="card mt-6 p-6 text-rose-300">Couldn't load this employee.</p>
      } @else {
        <div class="flex justify-center py-24 text-slate-400"><i class="fas fa-circle-notch fa-spin text-2xl"></i></div>
      }
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EmployeeDetailPage {
  readonly id = input.required<string>();

  private readonly api = inject(EmployeeApi);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);

  protected readonly labels = ACCOUNT_TYPE_LABELS;
  protected readonly data = rxResource({ params: () => Number(this.id()), stream: ({ params }) => this.api.get(params) });
  protected readonly employee = computed(() => (this.data.hasValue() ? this.data.value() : undefined));

  protected async remove(): Promise<void> {
    const employee = this.employee();
    if (!employee) return;
    const confirmed = await this.confirm.ask({
      title: `Delete ${employee.fullName}?`,
      message: 'Their record, NID image and ID card image will be removed permanently.',
      danger: true,
    });
    if (!confirmed) return;
    this.api.delete(employee.id).subscribe({
      next: () => {
        this.toast.success('Employee deleted');
        void this.router.navigate(['/employees']);
      },
      error: (err: unknown) => this.toast.error(errorMessage(err)),
    });
  }
}
