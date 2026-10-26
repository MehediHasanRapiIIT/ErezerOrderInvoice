import { ChangeDetectionStrategy, Component, HostListener, inject, output, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Observable } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { errorMessage } from '../../../core/http/api-error';
import { ToastService } from '../../../core/notify/toast.service';
import { AssetApi } from '../data/asset.api';
import { AssetCategory } from '../data/asset.models';

/** Manage asset categories. Emits whether anything changed so the caller can refresh. */
@Component({
  selector: 'app-asset-categories-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="fixed inset-0 z-[85] flex items-center justify-center bg-black/60 p-4" (click)="close()">
      <div class="card flex max-h-[85vh] w-full max-w-lg flex-col shadow-2xl" role="dialog" aria-modal="true" aria-label="Asset categories" (click)="$event.stopPropagation()">
        <div class="flex items-center justify-between border-b border-line px-5 py-4">
          <h2 class="text-lg font-semibold text-white">Asset categories</h2>
          <button type="button" class="cursor-pointer text-slate-400 hover:text-white" (click)="close()" aria-label="Close"><i class="fas fa-times"></i></button>
        </div>

        <form class="flex gap-2 border-b border-line px-5 py-4" (submit)="$event.preventDefault(); add(newName.value); newName.value = ''">
          <input #newName class="field" placeholder="New category name" maxlength="60" aria-label="New category name" />
          <button type="submit" class="btn-primary shrink-0" [disabled]="busy()"><i class="fas fa-plus"></i> Add</button>
        </form>

        <ul class="flex-1 divide-y divide-line overflow-y-auto">
          @for (c of list(); track c.id) {
            <li class="flex items-center gap-3 px-5 py-3">
              @if (editingId() === c.id) {
                <input #rename class="field" [value]="c.name" maxlength="60" (keydown.enter)="saveName(c, rename.value)" (keydown.escape)="editingId.set(null)" aria-label="Category name" />
                <button type="button" class="btn-primary px-3" (click)="saveName(c, rename.value)" aria-label="Save"><i class="fas fa-check"></i></button>
              } @else {
                <div class="min-w-0 flex-1">
                  <p class="truncate text-sm font-medium" [class]="c.active ? 'text-white' : 'text-slate-500 line-through'">{{ c.name }}</p>
                  <p class="text-xs text-slate-500">{{ c.assetCount }} asset{{ c.assetCount === 1 ? '' : 's' }}{{ c.active ? '' : ' · inactive' }}</p>
                </div>
                <button type="button" class="cursor-pointer rounded p-2 text-slate-400 hover:bg-white/5 hover:text-white" (click)="editingId.set(c.id)" aria-label="Rename" title="Rename"><i class="fas fa-pen text-xs"></i></button>
                <button type="button" class="cursor-pointer rounded px-2 py-1 text-xs text-slate-300 hover:bg-white/5" (click)="toggle(c)">{{ c.active ? 'Deactivate' : 'Activate' }}</button>
                @if (canDelete && c.assetCount === 0) {
                  <button type="button" class="cursor-pointer rounded p-2 text-red-300 hover:bg-red-500/10" (click)="remove(c)" aria-label="Delete" title="Delete"><i class="fas fa-trash text-xs"></i></button>
                }
              }
            </li>
          } @empty {
            <li class="px-5 py-8 text-center text-sm text-slate-500">{{ categories.isLoading() ? 'Loading…' : 'No categories yet' }}</li>
          }
        </ul>
        <p class="border-t border-line px-5 py-3 text-xs text-slate-500">
          Categories that are already used can't be deleted; deactivate them to hide them from new assets.
        </p>
      </div>
    </div>
  `,
})
export class AssetCategoriesDialog {
  readonly closed = output<boolean>();

  private readonly api = inject(AssetApi);
  private readonly toast = inject(ToastService);
  private readonly auth = inject(AuthService);

  protected readonly canDelete = this.auth.hasPermission('ASSET_CATEGORIES');
  protected readonly categories = rxResource({ stream: () => this.api.categories() });
  protected readonly editingId = signal<number | null>(null);
  protected readonly busy = signal(false);
  private changed = false;

  protected list(): AssetCategory[] {
    return this.categories.hasValue() ? this.categories.value() : [];
  }

  @HostListener('document:keydown.escape')
  protected close(): void {
    if (this.editingId() === null) {
      this.closed.emit(this.changed);
    }
  }

  protected add(name: string): void {
    if (!name.trim()) {
      return;
    }
    this.run(this.api.createCategory(name.trim()), `Category “${name.trim()}” added`);
  }

  protected saveName(category: AssetCategory, name: string): void {
    if (!name.trim() || name.trim() === category.name) {
      this.editingId.set(null);
      return;
    }
    this.run(this.api.updateCategory(category.id, name.trim(), category.active), 'Category renamed');
  }

  protected toggle(category: AssetCategory): void {
    this.run(this.api.updateCategory(category.id, category.name, !category.active),
      category.active ? `“${category.name}” deactivated` : `“${category.name}” activated`);
  }

  protected remove(category: AssetCategory): void {
    this.run(this.api.deleteCategory(category.id), `“${category.name}” deleted`);
  }

  private run(request: Observable<unknown>, message: string): void {
    this.busy.set(true);
    request.subscribe({
      next: () => {
        this.changed = true;
        this.busy.set(false);
        this.editingId.set(null);
        this.toast.success(message);
        this.categories.reload();
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }
}
