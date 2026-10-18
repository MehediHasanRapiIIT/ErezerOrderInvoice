/** Mirrors com.erezer.admin.dashboard.DashboardSummary. */
export interface DashboardTotals {
  totalRevenue: number;
  productRevenue: number;
  deliveryCharges: number;
  returnRevenue: number;
  returnProductRevenue: number;
  returnDeliveryTotal: number;
  refunds: number;
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
  granularity: 'DAY' | 'MONTH';
  series: SeriesPoint[];
  topExpenseCategories: { name: string; total: number }[];
}
