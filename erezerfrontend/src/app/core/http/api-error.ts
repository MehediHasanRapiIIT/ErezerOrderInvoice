import { HttpErrorResponse } from '@angular/common/http';

/** RFC 9457 problem+json body produced by the backend's GlobalExceptionHandler. */
export interface ProblemDetail {
  status: number;
  title?: string;
  detail?: string;
  code?: string;
  fieldErrors?: Record<string, string>;
}

export function toProblem(error: unknown): ProblemDetail {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 0) {
      return { status: 0, code: 'NETWORK_ERROR', detail: 'Cannot reach the server. Check your connection.' };
    }
    const body = error.error;
    if (body && typeof body === 'object' && 'status' in body) {
      return body as ProblemDetail;
    }
    return { status: error.status, detail: error.message };
  }
  return { status: -1, detail: 'Something went wrong' };
}

export function errorMessage(error: unknown): string {
  return toProblem(error).detail ?? 'Something went wrong';
}

/** Like {@link errorMessage}, for requests made with responseType 'blob' (the problem body arrives as a Blob). */
export async function readBlobError(error: unknown): Promise<string> {
  if (error instanceof HttpErrorResponse && error.error instanceof Blob) {
    try {
      const body = JSON.parse(await error.error.text()) as ProblemDetail;
      return body.detail ?? 'Something went wrong';
    } catch {
      return 'Something went wrong';
    }
  }
  return errorMessage(error);
}
