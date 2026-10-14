/** Mirrors com.erezer.admin.common.web.PageResponse. */
export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
