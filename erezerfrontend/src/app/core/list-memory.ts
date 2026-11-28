import { Directive, inject, Injectable, input } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs';

/** Lists whose page, filters and search live in the address, so they can be returned to exactly. */
const LISTS = ['/orders', '/assets', '/employees', '/expenses', '/staff', '/customers'];

/**
 * Remembers the last address of each list (its page, filters and search), so "Back" from an order, asset or
 * employee returns to the same page instead of page 1. Started with the app, so it sees every visit.
 */
@Injectable({ providedIn: 'root' })
export class ListMemory {
  private readonly router = inject(Router);
  private readonly urls = new Map<string, string>();

  constructor() {
    this.router.events.pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd)).subscribe((e) => {
      const path = e.urlAfterRedirects.split(/[?#]/)[0];
      if (LISTS.includes(path)) {
        this.urls.set(path, e.urlAfterRedirects);
      }
    });
  }

  /** The list's last address, or the list itself when it hasn't been visited. */
  urlFor(path: string): string {
    return this.urls.get(path) ?? path;
  }

  /** Goes back to the list as it was left. */
  backTo(path: string): Promise<boolean> {
    return this.router.navigateByUrl(this.urlFor(path));
  }
}

/** A "Back to list" link that returns to the page and filters the list was left on: `<a appBackTo="/orders">`. */
@Directive({
  selector: 'a[appBackTo]',
  host: { '[attr.href]': 'url()', '(click)': 'go($event)' },
})
export class BackToList {
  private readonly memory = inject(ListMemory);

  readonly appBackTo = input.required<string>();

  protected url(): string {
    return this.memory.urlFor(this.appBackTo());
  }

  protected go(event: MouseEvent): void {
    // Let the browser open a new tab or window as usual.
    if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) {
      return;
    }
    event.preventDefault();
    void this.memory.backTo(this.appBackTo());
  }
}
