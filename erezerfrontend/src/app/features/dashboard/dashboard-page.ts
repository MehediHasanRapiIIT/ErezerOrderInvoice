import { DecimalPipe, NgTemplateOutlet, PercentPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Params, Router, RouterLink } from '@angular/router';
import { isoDate } from '../../shared/date-range';
import { TakaPipe } from '../../shared/ui/taka.pipe';
import { PLATFORM_LABELS } from '../orders/data/order-labels';
import { OrderPlatform } from '../orders/data/order.models';
import { DashboardApi } from './dashboard.api';
import { DashboardTotals } from './dashboard.models';
import { BdDatePipe } from '../../shared/ui/bd-date.pipe';
import { bdToday } from '../../shared/ui/bd-date.pipe';

type Preset = 'today' | '7d' | 'month' | 'last-month' | '90d' | 'year' | 'custom';

const PRESETS: { value: Preset; label: string }[] = [
  { value: 'today', label: 'Today' },
  { value: '7d', label: 'Last 7 days' },
  { value: 'month', label: 'This month' },
  { value: 'last-month', label: 'Last month' },
  { value: '90d', label: 'Last 90 days' },
  { value: 'year', label: 'This year' },
  { value: 'custom', label: 'Custom range' },
];

/** Which direction is good news for a metric; neutral ones are shown without colour. */
type Trend = 'up-good' | 'up-bad' | 'neutral';

interface Card {
  label: string;
  key: keyof DashboardTotals;
  hint: string;
  sub: string;
  trend: Trend;
}

function presetRange(preset: Preset): { from: string; to: string } {
  const now = bdToday();
  const day = (y: number, m: number, d: number) => isoDate(new Date(y, m, d));
  const y = now.getFullYear();
  const m = now.getMonth();
  const d = now.getDate();
  switch (preset) {
    case 'today':
      return { from: day(y, m, d), to: day(y, m, d) };
    case '7d':
      return { from: day(y, m, d - 6), to: day(y, m, d) };
    case 'last-month':
      return { from: day(y, m - 1, 1), to: day(y, m, 0) };
    case '90d':
      return { from: day(y, m, d - 89), to: day(y, m, d) };
    case 'year':
      return { from: day(y, 0, 1), to: day(y, m, d) };
    default:
      return { from: day(y, m, 1), to: day(y, m, d) };
  }
}

@Component({
  selector: 'app-dashboard-page',
  imports: [BdDatePipe, DecimalPipe, NgTemplateOutlet, PercentPipe, RouterLink, TakaPipe],
  templateUrl: './dashboard-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DashboardPage {
  private readonly api = inject(DashboardApi);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly presets = PRESETS;
  protected platformLabel(name: string | null | undefined): string {
    return name ? (PLATFORM_LABELS[name as OrderPlatform] ?? name) : 'Not set';
  }
  protected readonly today = isoDate(bdToday());

  private readonly params = toSignal(this.route.queryParamMap, { initialValue: this.route.snapshot.queryParamMap });
  protected readonly preset = computed<Preset>(() => (this.params().get('range') as Preset | null) ?? 'month');
  protected readonly range = computed(() => {
    if (this.preset() === 'custom') {
      const fallback = presetRange('month');
      return { from: this.params().get('from') ?? fallback.from, to: this.params().get('to') ?? fallback.to };
    }
    return presetRange(this.preset());
  });

  protected readonly summary = rxResource({
    params: () => this.range(),
    stream: ({ params }) => this.api.summary(params.from, params.to),
  });
  protected readonly data = computed(() => (this.summary.hasValue() ? this.summary.value() : undefined));

  protected readonly salesCards = computed<Card[]>(() => {
    const c = this.data()?.current;
    return [
      { label: 'Total Revenue', key: 'totalRevenue', trend: 'up-good',
        hint: 'What customers paid for delivered orders, including the delivery charge',
        sub: `${c?.deliveredOrders ?? 0} deliveries` },
      { label: 'Product Revenue', key: 'productRevenue', trend: 'up-good',
        hint: 'Total revenue minus delivery charges: product sales after discounts',
        sub: 'Product price − delivery charge' },
      { label: 'Total Delivery Charge', key: 'deliveryCharges', trend: 'neutral',
        hint: 'Delivery charges collected from customers; this is passed on to the courier',
        sub: 'Collected, paid to courier' },
      { label: 'Total Courier Cost', key: 'courierCost', trend: 'up-bad',
        hint: "Everything paid to couriers in this period: each delivered parcel's real courier charge (the delivery "
          + 'charge when unknown), exchanges included, plus the courier cost of returns',
        sub: `Delivery ${taka(c?.deliveryCourierCost)} · Returns ${taka(c?.returnDeliveryTotal)}` },
    ];
  });

  protected readonly returnCards = computed<Card[]>(() => {
    const c = this.data()?.current;
    return [
      { label: 'Return Revenue', key: 'returnRevenue', trend: 'up-bad',
        hint: 'Value of returns recorded in this period: the full total of parcels refused at the door, '
          + "and the products' value of returns after delivery (the delivery charge is not refunded)",
        sub: `${c?.returnedOrders ?? 0} returns` },
      { label: 'Return Product Revenue', key: 'returnProductRevenue', trend: 'up-bad',
        hint: 'Product value of returned orders (order total minus delivery charge)',
        sub: `${c?.refusedOrders ?? 0} refused at the door` },
      { label: 'Return Product Delivery Total', key: 'returnDeliveryTotal', trend: 'up-bad',
        hint: 'What returns cost in courier fees: delivery + return charge when refused at the door, '
          + 'only the return charge when returned after delivery',
        sub: 'Courier cost of returns' },
    ];
  });

  /** Change vs the previous period of equal length, as a fraction; null when there is nothing to compare. */
  protected change(key: keyof DashboardTotals): number | null {
    const data = this.data();
    if (!data) {
      return null;
    }
    const current = data.current[key];
    const previous = data.previous[key];
    if (previous === 0) {
      return null;
    }
    return (current - previous) / Math.abs(previous);
  }

  protected changeTone(key: keyof DashboardTotals, trend: Trend): string {
    const delta = this.change(key);
    if (delta === null || delta === 0 || trend === 'neutral') {
      return 'text-slate-400';
    }
    const good = trend === 'up-good' ? delta > 0 : delta < 0;
    return good ? 'text-emerald-300' : 'text-red-300';
  }

  protected value(key: keyof DashboardTotals): number | null {
    return this.data()?.current[key] ?? null;
  }

  protected setPreset(value: string): void {
    const changes: Params = { range: value === 'month' ? null : value };
    if (value === 'custom') {
      changes['from'] = this.range().from;
      changes['to'] = this.range().to;
    } else {
      changes['from'] = null;
      changes['to'] = null;
    }
    this.updateParams(changes);
  }

  protected setCustom(key: 'from' | 'to', value: string): void {
    if (value) {
      this.updateParams({ range: 'custom', [key]: value });
    }
  }

  private updateParams(changes: Params): void {
    void this.router.navigate([], { relativeTo: this.route, queryParams: changes, queryParamsHandling: 'merge' });
  }
}

function taka(value: number | undefined): string {
  return `৳${(value ?? 0).toLocaleString('en-US', { maximumFractionDigits: 2 })}`;
}
