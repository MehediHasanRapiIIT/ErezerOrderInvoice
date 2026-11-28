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

  it('shows first, last and two pages either side, with gaps', () => {
    expect(render(9, 31)).toEqual(['1', '…', '8', '9', '10', '11', '12', '…', '31', 'Go']);
  });

  it('shows the first five pages at the start and the last five at the end', () => {
    expect(render(0, 31)).toEqual(['1', '2', '3', '4', '5', '…', '31', 'Go']);
    expect(render(30, 31)).toEqual(['1', '…', '27', '28', '29', '30', '31', 'Go']);
  });

  it('fills a one-page gap instead of showing "…"', () => {
    expect(render(4, 31)).toEqual(['1', '2', '3', '4', '5', '6', '7', '…', '31', 'Go']);
  });

  it('leaves out the go-to box on short lists', () => {
    expect(render(0, 3)).toEqual(['1', '2', '3']);
  });

  it('goes to a typed page, kept within the list', () => {
    const fixture = TestBed.createComponent(Pagination);
    fixture.componentRef.setInput('page', 0);
    fixture.componentRef.setInput('size', 10);
    fixture.componentRef.setInput('totalElements', 306);
    fixture.componentRef.setInput('totalPages', 31);
    fixture.detectChanges();
    const emitted: number[] = [];
    fixture.componentInstance.pageChange.subscribe((p) => emitted.push(p));
    const nav = fixture.nativeElement as HTMLElement;
    const box = nav.querySelector('input') as HTMLInputElement;
    const form = nav.querySelector('form') as HTMLFormElement;
    for (const typed of ['5', '99', '0']) {
      box.value = typed;
      form.dispatchEvent(new Event('submit'));
    }
    expect(emitted).toEqual([4, 30]);
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
