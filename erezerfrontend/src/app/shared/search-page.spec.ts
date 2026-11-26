import { SearchPage } from './search-page';

describe('SearchPage', () => {
  it('goes back to the page the search started from when it is cleared', () => {
    const memory = new SearchPage();
    expect(memory.pageFor('', 'ab', 2)).toBe(0);
    // Refining the search or paging through results keeps the original page.
    expect(memory.pageFor('ab', 'abc', 1)).toBe(0);
    expect(memory.pageFor('abc', '', 0)).toBe(2);
  });

  it('starts at the first page when nothing was remembered', () => {
    const memory = new SearchPage();
    expect(memory.pageFor('', '', 3)).toBe(0);
    memory.pageFor('', 'x', 4);
    memory.reset();
    expect(memory.pageFor('x', '', 0)).toBe(0);
  });
});
