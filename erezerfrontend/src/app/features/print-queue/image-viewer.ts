import { ChangeDetectionStrategy, Component, computed, input, model, output, signal } from '@angular/core';

export interface ViewerImage {
  url: string;
  caption: string;
}

/**
 * Full-screen picture viewer: arrows (or ← →) move between pictures, a click on the picture shows it at its real size,
 * Esc or the background closes it.
 */
@Component({
  selector: 'app-image-viewer',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(document:keydown)': 'onKey($event)' },
  template: `
    <div class="fixed inset-0 z-[90] flex flex-col bg-black/95" role="dialog" aria-modal="true" aria-label="Product picture" (click)="closed.emit()">
      <div class="flex items-center justify-between gap-3 p-3 text-sm text-slate-200" (click)="$event.stopPropagation()">
        <p class="min-w-0 truncate">{{ current().caption }}</p>
        <div class="flex shrink-0 items-center gap-2">
          <span class="text-slate-400">{{ index() + 1 }} / {{ images().length }}</span>
          <a [href]="current().url" target="_blank" rel="noopener" class="btn-secondary px-2 py-1" title="Open the original" aria-label="Open the original">
            <i class="fas fa-external-link-alt"></i>
          </a>
          <button type="button" class="btn-secondary px-2 py-1" (click)="closed.emit()" aria-label="Close"><i class="fas fa-times"></i></button>
        </div>
      </div>
      <div class="relative flex min-h-0 flex-1 items-center justify-center p-2" [class.overflow-auto]="zoomed()">
        @if (images().length > 1) {
          <button type="button" class="absolute left-2 top-1/2 z-10 -translate-y-1/2 rounded-full bg-black/60 px-3 py-2 text-white hover:bg-black/80"
                  (click)="$event.stopPropagation(); move(-1)" aria-label="Previous picture"><i class="fas fa-chevron-left"></i></button>
          <button type="button" class="absolute right-2 top-1/2 z-10 -translate-y-1/2 rounded-full bg-black/60 px-3 py-2 text-white hover:bg-black/80"
                  (click)="$event.stopPropagation(); move(1)" aria-label="Next picture"><i class="fas fa-chevron-right"></i></button>
        }
        <img [src]="current().url" [alt]="current().caption"
             [class]="zoomed() ? 'max-w-none cursor-zoom-out self-start' : 'h-full w-full cursor-zoom-in object-contain'"
             (click)="$event.stopPropagation(); zoomed.set(!zoomed())" />
      </div>
      <p class="p-2 text-center text-xs text-slate-500">Click the picture to zoom · ← → to move · Esc to close</p>
    </div>
  `,
})
export class ImageViewer {
  readonly images = input.required<ViewerImage[]>();
  readonly index = model(0);
  readonly closed = output<void>();

  protected readonly zoomed = signal(false);
  protected readonly current = computed(() => this.images()[this.index()] ?? this.images()[0]);

  protected move(step: number): void {
    const count = this.images().length;
    this.index.set((this.index() + step + count) % count);
    this.zoomed.set(false);
  }

  protected onKey(event: KeyboardEvent): void {
    if (event.key === 'Escape') this.closed.emit();
    else if (event.key === 'ArrowLeft') this.move(-1);
    else if (event.key === 'ArrowRight') this.move(1);
  }
}
