import { ChangeDetectionStrategy, Component, inject, input, OnInit, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { errorMessage } from '../../core/http/api-error';
import { ToastService } from '../../core/notify/toast.service';
import { isoDate } from '../../shared/date-range';
import { bdToday } from '../../shared/ui/bd-date.pipe';
import { SizeField } from '../stock/size-field';
import { VariantPicker } from '../stock/variant-picker';
import { Reject, REJECT_KIND_LABELS, REJECT_KINDS, RejectApi, RejectKind } from './data/reject.api';

/** Add or edit a reject. A stock variant loses the pieces; "Other" is only a typed name. */
@Component({
  selector: 'app-reject-form-dialog',
  imports: [FormsModule, SizeField, VariantPicker],
  template: `
    <div class="fixed inset-0 z-[85] flex items-start justify-center overflow-y-auto bg-black/60 p-4 sm:items-center">
      <form class="card w-full max-w-md p-5" (ngSubmit)="save()">
        <h2 class="text-lg font-semibold text-white">{{ reject() ? 'Edit reject' : 'Add reject' }}</h2>
        <p class="text-sm text-slate-400">{{ other() ? 'Other does not change the stock.' : 'This takes the pieces out of stock.' }}</p>

        <span class="field-label mt-4 block">Variant *</span>
        <app-variant-picker [allowOther]="true" [(name)]="name" [(variantId)]="variantId" [(other)]="other" placeholder="Type to search, or choose Other" />

        @if (other()) {
          <label class="field-label mt-3 block" for="reject-other">Variant name *</label>
          <input id="reject-other" name="other" class="field" maxlength="120" [(ngModel)]="otherName" />
        }

        <label class="field-label mt-3 block" for="reject-kind">Type *</label>
        <select id="reject-kind" name="kind" class="field" [(ngModel)]="kind">
          @for (k of kinds; track k) { <option [ngValue]="k">{{ kindLabels[k] }}</option> }
        </select>

        <div class="mt-3 grid grid-cols-2 gap-3">
          <div>
            <span class="field-label">Size{{ variantId() !== null ? ' *' : '' }}</span>
            <app-size-field [variantId]="variantId()" [(stockItemId)]="stockItemId" [(size)]="size" listId="reject-sizes" />
          </div>
          <div>
            <label class="field-label" for="reject-quantity">Quantity *</label>
            <input id="reject-quantity" name="quantity" type="number" min="1" step="1" class="field" [(ngModel)]="quantity" />
          </div>
        </div>

        <label class="field-label mt-3 block" for="reject-date">Date *</label>
        <input id="reject-date" name="date" type="date" class="field" [max]="today" [(ngModel)]="date" />

        <label class="field-label mt-3 block" for="reject-note">Note</label>
        <input id="reject-note" name="note" class="field" maxlength="300" placeholder="e.g. Print came out wrong" [(ngModel)]="note" />

        <div class="mt-5 flex justify-end gap-2">
          <button type="button" class="btn-secondary" (click)="closed.emit(false)">Cancel</button>
          <button type="submit" class="btn-primary" [disabled]="saving()">{{ reject() ? 'Save changes' : 'Add reject' }}</button>
        </div>
      </form>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RejectFormDialog implements OnInit {
  private readonly api = inject(RejectApi);
  private readonly toast = inject(ToastService);

  /** The reject being edited; empty to add one. */
  readonly reject = input<Reject | null>(null);
  /** True when something was saved. */
  readonly closed = output<boolean>();

  protected readonly kinds = REJECT_KINDS;
  protected readonly kindLabels = REJECT_KIND_LABELS;
  protected readonly today = isoDate(bdToday());
  protected readonly name = signal('');
  protected readonly variantId = signal<number | null>(null);
  protected readonly other = signal(false);
  protected readonly stockItemId = signal<number | null>(null);
  protected readonly size = signal('');
  protected readonly saving = signal(false);
  protected otherName = '';
  protected kind: RejectKind = 'SOLID';
  protected quantity: number | null = 1;
  protected date = this.today;
  protected note = '';

  ngOnInit(): void {
    const reject = this.reject();
    if (!reject) return;
    this.other.set(!reject.fromStock);
    this.name.set(reject.fromStock ? reject.variantName : 'Other');
    this.variantId.set(reject.fromStock ? (reject.variantId ?? null) : null);
    this.stockItemId.set(reject.stockItemId ?? null);
    this.size.set(reject.size ?? '');
    this.otherName = reject.fromStock ? '' : reject.variantName;
    this.kind = reject.kind;
    this.quantity = reject.quantity;
    this.date = reject.date;
    this.note = reject.note ?? '';
  }

  protected save(): void {
    const quantity = Number(this.quantity);
    if (this.variantId() === null && !this.other()) {
      this.toast.error('Choose a variant from the list, or Other');
      return;
    }
    if (this.other() && !this.otherName.trim()) {
      this.toast.error('Write the variant name');
      return;
    }
    if (this.variantId() !== null && this.stockItemId() === null) {
      this.toast.error('Choose the size');
      return;
    }
    if (!Number.isInteger(quantity) || quantity < 1) {
      this.toast.error('Enter how many pieces');
      return;
    }
    const body = {
      date: this.date,
      variantId: this.variantId(),
      stockItemId: this.stockItemId(),
      otherName: this.other() ? this.otherName.trim() : null,
      size: this.other() ? this.size().trim() || null : null,
      kind: this.kind,
      quantity,
      note: this.note.trim() || null,
    };
    const reject = this.reject();
    this.saving.set(true);
    (reject ? this.api.update(reject.id, { ...body, version: reject.version }) : this.api.create(body)).subscribe({
      next: () => {
        this.toast.success(reject ? 'Changes saved' : 'Reject added');
        this.closed.emit(true);
      },
      error: (err: unknown) => {
        this.toast.error(errorMessage(err));
        this.saving.set(false);
      },
    });
  }
}
