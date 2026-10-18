import { TestBed } from '@angular/core/testing';
import { Pagination } from './pagination';
import { TakaPipe } from './taka.pipe';

describe('TakaPipe', () => {
  const pipe = new TakaPipe();

  it('formats with the taka sign, grouping and two decimals', () => {
    expect(pipe.transform(1810)).toBe('৳ 1,810.00');
    expect(pipe.transform(0.5)).toBe('৳ 0.50');
    expect(pipe.transform(-4090.5)).toBe('−৳ 4,090.50');
  });

  it('renders a dash for missing values', () => {
    expect(pipe.transform(null)).toBe('—');
    expect(pipe.transform(undefined)).toBe('—');
  });
});

describe('Pagination', () => {
  function render(page: number, totalPages: number): string[] {
    const fixture = TestBed.createComponent(Pagination);
    fixture.componentRef.setInput('page', page);
    fixture.componentRef.setInput('size', 20);
    fixture.componentRef.setInput('totalElements', totalPages * 20);
    fixture.componentRef.setInput('totalPages', totalPages);
    fixture.detectChanges();
    const nav = fixture.nativeElement as HTMLElement;
    return Array.from(nav.querySelectorAll('button, span.px-1'))
      .map((el) => el.textContent?.trim() ?? '')
      .filter((text) => text !== '');
  }

  it('shows first, last and neighbours with gaps', () => {
    expect(render(5, 20)).toEqual(['1', '…', '5', '6', '7', '…', '20']);
  });

  it('shows the row range', () => {
    const fixture = TestBed.createComponent(Pagination);
    fixture.componentRef.setInput('page', 1);
    fixture.componentRef.setInput('size', 20);
    fixture.componentRef.setInput('totalElements', 35);
    fixture.componentRef.setInput('totalPages', 2);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('21–35');
  });
});
