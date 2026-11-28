import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { environment } from '../../../../environments/environment';
import { readBlobError } from '../../../core/http/api-error';
import { showBlobInTab } from '../../../core/http/file-download';
import { ToastService } from '../../../core/notify/toast.service';

/** Most invoices printed at once (as on the server): each can carry product pictures. */
export const MAX_BULK_INVOICES = 100;

/** Opens the invoices of several orders as one PDF in a new tab, each invoice on its own pages. */
@Injectable({ providedIn: 'root' })
export class BulkInvoices {
  private readonly http = inject(HttpClient);
  private readonly toast = inject(ToastService);

  /** Resolves when the PDF is shown (or failed). Call it from the click, so the new tab isn't blocked. */
  open(orderIds: readonly number[]): Promise<void> {
    if (orderIds.length > MAX_BULK_INVOICES) {
      this.toast.error(`Print at most ${MAX_BULK_INVOICES} invoices at a time`);
      return Promise.resolve();
    }
    const tab = window.open('', '_blank');
    return new Promise((resolve) => {
      this.http
        .get(`${environment.apiBaseUrl}/orders/invoices.pdf`, { params: { ids: orderIds.join(',') }, responseType: 'blob' })
        .subscribe({
          next: (blob) => {
            showBlobInTab(tab, blob, `invoices-${orderIds.length}.pdf`);
            resolve();
          },
          error: (err: unknown) => {
            tab?.close();
            void readBlobError(err).then((message) => this.toast.error(message));
            resolve();
          },
        });
    });
  }
}
