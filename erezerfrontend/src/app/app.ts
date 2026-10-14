import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ToastContainer } from './core/notify/toast-container';
import { ConfirmDialog } from './shared/ui/confirm-dialog';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, ToastContainer, ConfirmDialog],
  template: `
    <router-outlet />
    <app-toast-container />
    <app-confirm-dialog />
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {}
