import { OrderDetail, OrderStatus, StatusChange } from '../data/order.models';
import { buildTimeline } from './order-detail-page';

function order(status: OrderStatus, path: OrderStatus[]): OrderDetail {
  const history: StatusChange[] = path.map((to, i) => ({
    fromStatus: i === 0 ? undefined : path[i - 1],
    toStatus: to,
    changedAt: `2026-10-0${i + 1}T10:00:00Z`,
  }));
  return { status, history } as OrderDetail;
}

describe('buildTimeline', () => {
  it('marks earlier steps done, the current step current and later steps upcoming', () => {
    const steps = buildTimeline(order('PROCESSING', ['PLACED', 'ACCEPTED', 'IN_PRODUCTION', 'PROCESSING']));
    expect(steps.map((s) => s.state)).toEqual([
      'done', 'done', 'done', 'current', 'upcoming', 'upcoming', 'upcoming',
    ]);
    expect(steps[0].at).toBe('2026-10-01T10:00:00Z');
  });

  it('treats skipped steps as done', () => {
    const steps = buildTimeline(order('SHIPPED', ['PLACED', 'SHIPPED']));
    expect(steps.slice(0, 5).map((s) => s.state)).toEqual(['done', 'done', 'done', 'done', 'current']);
    expect(steps[1].at).toBeUndefined();
  });

  it('shows delivered as fully done', () => {
    const steps = buildTimeline(order('DELIVERED', ['PLACED', 'SHIPPED', 'DELIVERED']));
    expect(steps.every((s) => s.state === 'done')).toBe(true);
  });

  it('ends a cancelled order with a failed step after the last reached status', () => {
    const steps = buildTimeline(order('CANCELLED', ['PLACED', 'ACCEPTED', 'CANCELLED']));
    expect(steps.map((s) => [s.status, s.state])).toEqual([
      ['PLACED', 'done'],
      ['ACCEPTED', 'done'],
      ['CANCELLED', 'failed'],
    ]);
  });
});
