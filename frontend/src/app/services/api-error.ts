import { HttpErrorResponse } from '@angular/common/http';

/**
 * The reason the backend gave for a failed call, if any.
 *
 * The 400 and 403 responses of the API carry a JSON body `{ "error": "..." }`
 * (see `BadRequestExceptionMapper` and `ForbiddenExceptionMapper`), e.g.
 * "bob is already a member of this board". Showing that text is far more useful
 * than a bare "Could not add bob".
 */
export function apiReason(err: unknown): string | null {
  if (err instanceof HttpErrorResponse && typeof err.error?.error === 'string') {
    return err.error.error;
  }
  return null;
}
