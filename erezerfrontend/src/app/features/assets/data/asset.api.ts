import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { Asset, AssetCategory, AssetPage, AssetQuery, AssetRequest, AssetStatus, UploadedFile } from './asset.models';

@Injectable({ providedIn: 'root' })
export class AssetApi {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiBaseUrl}/assets`;
  private readonly categoriesUrl = `${environment.apiBaseUrl}/asset-categories`;

  list(query: AssetQuery): Observable<AssetPage> {
    return this.http.get<AssetPage>(this.baseUrl, {
      params: filterParams(query).set('page', query.page).set('size', query.size),
    });
  }

  exportExcel(query: AssetQuery): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.baseUrl}/export.xlsx`, {
      params: filterParams(query),
      responseType: 'blob',
      observe: 'response',
    });
  }

  locations(): Observable<string[]> {
    return this.http.get<string[]>(`${this.baseUrl}/locations`);
  }

  get(id: number): Observable<Asset> {
    return this.http.get<Asset>(`${this.baseUrl}/${id}`);
  }

  create(request: AssetRequest): Observable<Asset> {
    return this.http.post<Asset>(this.baseUrl, request);
  }

  update(id: number, request: AssetRequest): Observable<Asset> {
    return this.http.put<Asset>(`${this.baseUrl}/${id}`, request);
  }

  changeStatus(id: number, body: { status: AssetStatus; date: string; salePrice: number | null; note: string | null }): Observable<Asset> {
    return this.http.post<Asset>(`${this.baseUrl}/${id}/status`, body);
  }

  addRepair(id: number, body: { date: string; description: string; cost: number | null }): Observable<Asset> {
    return this.http.post<Asset>(`${this.baseUrl}/${id}/repairs`, body);
  }

  deleteRepair(id: number, eventId: number): Observable<Asset> {
    return this.http.delete<Asset>(`${this.baseUrl}/${id}/repairs/${eventId}`);
  }

  delete(id: number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  /** Photo or receipt (JPEG/PNG/WebP, or PDF for receipts), uploaded before the asset is saved. */
  upload(file: File): Observable<UploadedFile> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.http.post<UploadedFile>(`${environment.apiBaseUrl}/files/receipts`, form);
  }

  categories(): Observable<AssetCategory[]> {
    return this.http.get<AssetCategory[]>(this.categoriesUrl);
  }

  createCategory(name: string): Observable<AssetCategory> {
    return this.http.post<AssetCategory>(this.categoriesUrl, { name });
  }

  updateCategory(id: number, name: string, active: boolean): Observable<void> {
    return this.http.put<void>(`${this.categoriesUrl}/${id}`, { name, active });
  }

  deleteCategory(id: number): Observable<void> {
    return this.http.delete<void>(`${this.categoriesUrl}/${id}`);
  }
}

function filterParams(query: AssetQuery): HttpParams {
  let params = new HttpParams();
  for (const key of ['q', 'categoryId', 'status', 'location', 'from', 'to', 'warranty'] as const) {
    const value = query[key];
    if (value !== undefined && value !== null && value !== '') {
      params = params.set(key, value);
    }
  }
  return params;
}
