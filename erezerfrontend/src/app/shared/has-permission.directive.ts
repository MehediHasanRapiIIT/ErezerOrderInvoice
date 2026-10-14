import { Directive, effect, inject, input, TemplateRef, ViewContainerRef } from '@angular/core';
import { Permission } from '../core/auth/auth.models';
import { AuthService } from '../core/auth/auth.service';

/**
 * Renders its content only when the user holds at least one of the given permissions.
 *
 * <button *appHasPermission="'ORDER_CREATE'">Add order</button>
 * <div *appHasPermission="['EXPENSE_EDIT', 'EXPENSE_DELETE']">...</div>
 */
@Directive({ selector: '[appHasPermission]' })
export class HasPermissionDirective {
  readonly appHasPermission = input.required<Permission | Permission[]>();

  private readonly auth = inject(AuthService);
  private readonly template = inject(TemplateRef<unknown>);
  private readonly container = inject(ViewContainerRef);
  private rendered = false;

  constructor() {
    effect(() => {
      const value = this.appHasPermission();
      const allowed = this.auth.hasAnyPermission(Array.isArray(value) ? value : [value]);
      if (allowed && !this.rendered) {
        this.container.createEmbeddedView(this.template);
        this.rendered = true;
      } else if (!allowed && this.rendered) {
        this.container.clear();
        this.rendered = false;
      }
    });
  }
}
