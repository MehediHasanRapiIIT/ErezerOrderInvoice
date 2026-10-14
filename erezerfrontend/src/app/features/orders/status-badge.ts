import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { STATUS_LABELS, STATUS_STYLES } from './data/order-labels';
import { OrderStatus } from './data/order.models';

@Component({
  selector: 'app-status-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span
      class="inline-flex items-center gap-1.5 whitespace-nowrap rounded-full border px-2.5 py-0.5 text-xs font-semibold uppercase tracking-wide"
      [class]="style()"
    >
      <span class="h-1.5 w-1.5 rounded-full bg-current"></span>
      {{ label() }}
    </span>
  `,
})
export class StatusBadge {
  readonly status = input.required<OrderStatus>();
  protected readonly label = computed(() => STATUS_LABELS[this.status()]);
  protected readonly style = computed(() => STATUS_STYLES[this.status()]);
}
