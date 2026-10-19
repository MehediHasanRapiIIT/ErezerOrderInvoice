/** Mirrors com.erezer.admin.dashboard.DashboardSummary. */
export interface DashboardTotals {
  totalRevenue: number;
  productRevenue: number;
  deliveryCharges: number;
  /** Delivery charge collected minus what the courier really charged (when known). */
  deliveryChargeDifference: number;
  returnRevenue: number;
  returnProductRevenue: number;
  returnDeliveryTotal: number;
  /** Return charges of refused parcels that the customer paid (kept from the advance). */
  returnChargesRecovered: number;
  refunds: number;
  exchangeRefunds: number;
  exchangeCost: number;
  /** Everything paid to couriers in the period (deliveries + returns). */
  courierCost: number;
  deliveryCourierCost: number;
  expenses: number;
  netProfit: number;
  deliveredOrders: number;
  returnedOrders: number;
  refusedOrders: number;
  expenseCount: number;
}

export interface SeriesPoint {
  bucket: string;
  productRevenue: number;
  expenses: number;
}

export interface DashboardSummary {
  from: string;
  to: string;
  current: DashboardTotals;
  previous: DashboardTotals;
  orders: { placed: number; cancelled: number; delivered: number; returned: number; returnRate: number | null };
  /** Exchanges delivered in the period, counted in items. */
  exchanges: {
    exchanges: number;
    items: number;
    itemsPaidByCustomer: number;
    itemsPaidByErezer: number;
    deliveryCollected: number;
    erezerCost: number;
  };
  /** Returns recorded in the period, counted in items, split by who paid the return charge. */
  returns: {
    returns: number;
    items: number;
    itemsPaidByCustomer: number;
    itemsPaidByErezer: number;
    chargesPaidByCustomer: number;
    chargesPaidByErezer: number;
  };
  granularity: 'DAY' | 'MONTH';
  series: SeriesPoint[];
  topExpenseCategories: { name: string; total: number }[];
}
