import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { errorMessage } from '../../../core/http/api-error';
import { ToastService } from '../../../core/notify/toast.service';
import { isoDate } from '../../../shared/date-range';
import { HasPermissionDirective } from '../../../shared/has-permission.directive';
import { PAYMENT_LABELS } from '../../../shared/payment-methods';
import { BdDatePipe, bdToday } from '../../../shared/ui/bd-date.pipe';
import { ConfirmService } from '../../../shared/ui/confirm-dialog';
import { TakaPipe } from '../../../shared/ui/taka.pipe';
import { AssetApi } from '../data/asset.api';
import { Asset, ASSET_STATUS_CLASSES, ASSET_STATUS_LABELS, ASSET_STATUSES, AssetEvent, AssetEventKind, AssetStatus } from '../data/asset.models';

const EVENT_ICONS: Record<AssetEventKind, string> = {
  CREATED: 'fa-plus text-emerald-300',
  UPDATED: 'fa-pen text-slate-300',
  MOVED: 'fa-map-marker-alt text-sky-300',
  STATUS: 'fa-exchange-alt text-brand-light',
  REPAIR: 'fa-tools text-amber-300',
};

/** One asset: details, status changes, repair / service records and the full history. */
@Component({
  selector: 'app-asset-detail-page',
  imports: [BdDatePipe, FormsModule, RouterLink, HasPermissionDirective, TakaPipe],
  templateUrl: './asset-detail-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AssetDetailPage {
  readonly id = input.required<string>();

  private readonly api = inject(AssetApi);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);
  protected readonly auth = inject(AuthService);

  protected readonly statusLabels = ASSET_STATUS_LABELS;
  protected readonly statusClasses = ASSET_STATUS_CLASSES;
  protected readonly paymentLabels = PAYMENT_LABELS;
  protected readonly eventIcons = EVENT_ICONS;
  protected readonly today = isoDate(bdToday());

  protected readonly resource = rxResource({ params: () => Number(this.id()), stream: ({ params }) => this.api.get(params) });
  protected readonly asset = computed(() => (this.resource.hasValue() ? this.resource.value() : undefined));
  protected readonly repairs = computed(() => (this.asset()?.events ?? []).filter((e) => e.kind === 'REPAIR'));
  protected readonly otherStatuses = computed(() => ASSET_STATUSES.filter((s) => s !== this.asset()?.status));
  protected readonly warrantyState = computed(() => {
    const w = this.asset()?.warrantyUntil;
    if (!w) return '';
    if (w < this.today) return 'expired';
    const soon = isoDate(new Date(bdToday().getTime() + 30 * 86_400_000));
    return w <= soon ? 'soon' : 'valid';
  });

  // Status change
  protected statusForm: { status: AssetStatus | ''; date: string; salePrice: number | null; note: string } = {
    status: '',
    date: this.today,
    salePrice: null,
    note: '',
  };
  protected readonly savingStatus = signal(false);

  // Repair
  protected repairForm: { date: string; description: string; cost: number | null } = { date: this.today, description: '', cost: null };
  protected readonly savingRepair = signal(false);
  protected readonly showRepairForm = signal(false);

  constructor() {
    // Reset the status form when the asset (re)loads.
    effect(() => {
      const a = this.asset();
      if (a) {
        this.statusForm = { status: '', date: this.today, salePrice: null, note: '' };
      }
    });
  }

  protected submitStatus(a: Asset): void {
    const f = this.statusForm;
    if (!f.status) {
      this.toast.error('Choose the new status');
      return;
    }
    if (f.status === 'SOLD' && (f.salePrice === null || `${f.salePrice}` === '' || Number(f.salePrice) < 0)) {
      this.toast.error('Enter what it was sold for (0 if given away)');
      return;
    }
    this.savingStatus.set(true);
    this.api
      .changeStatus(a.id, {
        status: f.status,
        date: f.date,
        salePrice: f.status === 'SOLD' ? Number(f.salePrice) : null,
        note: f.note.trim() || null,
      })
      .subscribe({
        next: (updated) => this.done(updated, `Marked ${ASSET_STATUS_LABELS[updated.status].toLowerCase()}`, this.savingStatus),
        error: (err: unknown) => this.failed(err, this.savingStatus),
      });
  }

  protected submitRepair(a: Asset): void {
    const f = this.repairForm;
    if (!f.description.trim()) {
      this.toast.error('Describe the repair or service');
      return;
    }
    const cost = f.cost === null || `${f.cost}` === '' ? null : Number(f.cost);
    if (cost !== null && !(cost >= 0)) {
      this.toast.error('Enter a cost of 0 or more');
      return;
    }
    this.savingRepair.set(true);
    this.api.addRepair(a.id, { date: f.date, description: f.description.trim(), cost }).subscribe({
      next: (updated) => {
        this.repairForm = { date: this.today, description: '', cost: null };
        this.showRepairForm.set(false);
        this.done(updated, 'Repair recorded', this.savingRepair);
      },
      error: (err: unknown) => this.failed(err, this.savingRepair),
    });
  }

  protected async removeRepair(a: Asset, e: AssetEvent): Promise<void> {
    const confirmed = await this.confirm.ask({
      title: 'Remove this repair record?',
      message: `“${e.description}” will be removed from the repair list.`,
      confirmLabel: 'Remove',
      danger: true,
    });
    if (!confirmed) return;
    this.api.deleteRepair(a.id, e.id).subscribe({
      next: (updated) => this.done(updated, 'Repair removed'),
      error: (err: unknown) => this.toast.error(errorMessage(err)),
    });
  }

  protected async remove(a: Asset): Promise<void> {
    const confirmed = await this.confirm.ask({
      title: 'Delete this asset?',
      message: `“${a.name}” will be removed from the list and the totals. Use Sold or Disposed instead if it simply left the shop.`,
      confirmLabel: 'Delete asset',
      danger: true,
    });
    if (!confirmed) return;
    this.api.delete(a.id).subscribe({
      next: () => {
        this.toast.success('Asset deleted');
        void this.router.navigate(['/assets']);
      },
      error: (err: unknown) => this.toast.error(errorMessage(err)),
    });
  }

  protected isPdf(contentType: string): boolean {
    return contentType === 'application/pdf';
  }

  private done(updated: Asset, message: string, busy?: ReturnType<typeof signal<boolean>>): void {
    this.resource.set(updated);
    busy?.set(false);
    this.toast.success(message);
  }

  private failed(err: unknown, busy: ReturnType<typeof signal<boolean>>): void {
    busy.set(false);
    this.toast.error(errorMessage(err));
  }
}
