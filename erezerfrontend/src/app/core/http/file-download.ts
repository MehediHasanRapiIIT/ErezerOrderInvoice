import { HttpResponse } from '@angular/common/http';

/** Saves a blob as a file via a temporary link. */
export function saveBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 30_000);
}

/**
 * Opens a blob (e.g. a PDF) in a browser tab that was opened synchronously in the click handler,
 * so pop-up blockers allow it. Falls back to a download when the tab could not be opened.
 */
export function showBlobInTab(tab: Window | null, blob: Blob, fileName: string): void {
  if (!tab || tab.closed) {
    saveBlob(blob, fileName);
    return;
  }
  const url = URL.createObjectURL(blob);
  tab.location.href = url;
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

/** File name from a Content-Disposition header, if present. */
export function fileNameFrom(response: HttpResponse<Blob>, fallback: string): string {
  const header = response.headers.get('Content-Disposition') ?? '';
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(header);
  return match ? decodeURIComponent(match[1]) : fallback;
}
