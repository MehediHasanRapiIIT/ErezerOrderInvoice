import { TestBed } from '@angular/core/testing';
import { RevenueChart } from './revenue-chart';

describe('RevenueChart', () => {
  function render(points: { bucket: string; productRevenue: number; expenses: number }[]) {
    const fixture = TestBed.createComponent(RevenueChart);
    fixture.componentRef.setInput('points', points);
    fixture.componentRef.setInput('granularity', 'DAY');
    fixture.detectChanges();
    return fixture;
  }

  it('scales the tallest bar to the rounded axis maximum and labels ticks compactly', () => {
    const fixture = render([
      { bucket: '2026-10-01', productRevenue: 9000, expenses: 2500 },
      { bucket: '2026-10-02', productRevenue: 0, expenses: 400 },
    ]);
    const chart = fixture.componentInstance as unknown as {
      ticks: () => { label: string }[];
      bars: () => { revenue: { height: number }; expense: { height: number } }[];
      plotHeight: number;
    };
    expect(chart.ticks().map((t) => t.label)).toEqual(['৳0', '৳2.5k', '৳5k', '৳7.5k', '৳10k']);
    const bars = chart.bars();
    expect(bars[0].revenue.height).toBeCloseTo(chart.plotHeight * 0.9);
    expect(bars[1].revenue.height).toBe(0);
  });

  it('offers the same numbers as a table', () => {
    const fixture = render([{ bucket: '2026-10-01', productRevenue: 1200, expenses: 200 }]);
    const button = (fixture.nativeElement as HTMLElement).querySelector('button')!;
    button.click();
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('৳ 1,200.00');
    expect(text).toContain('৳ 1,000.00'); // difference
  });
});
