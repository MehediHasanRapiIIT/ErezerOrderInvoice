/**
 * Remembers which page a list was on when a search started, so clearing the search box goes back to that page
 * instead of page 1. Typing a search still starts at page 1, where the best matches are.
 */
export class SearchPage {
  private before: number | null = null;

  /** The page to show after the search text changes from {@code previous} to {@code next}. */
  pageFor(previous: string, next: string, current: number): number {
    if (next && !previous) {
      this.before = current;
    }
    if (next) {
      return 0;
    }
    const back = this.before ?? 0;
    this.before = null;
    return back;
  }

  /** Forgets the remembered page, e.g. when every filter is cleared. */
  reset(): void {
    this.before = null;
  }
}
