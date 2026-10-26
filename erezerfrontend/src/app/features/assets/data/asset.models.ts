import { PaymentMethod } from '../../../shared/payment-methods';

/** Mirrors com.erezer.admin.asset.dto.AssetDtos. */
export type AssetStatus = 'IN_USE' | 'UNDER_REPAIR' | 'IN_STORAGE' | 'DAMAGED' | 'SOLD' | 'DISPOSED';
export type AssetEventKind = 'CREATED' | 'UPDATED' | 'MOVED' | 'STATUS' | 'REPAIR';

export const ASSET_STATUS_LABELS: Record<AssetStatus, string> = {
  IN_USE: 'In use',
  UNDER_REPAIR: 'Under repair',
  IN_STORAGE: 'In storage',
  DAMAGED: 'Damaged',
  SOLD: 'Sold',
  DISPOSED: 'Disposed',
};

export const ASSET_STATUSES = Object.keys(ASSET_STATUS_LABELS) as AssetStatus[];
/** Statuses of things the shop still owns. */
export const OWNED_STATUSES: AssetStatus[] = ['IN_USE', 'UNDER_REPAIR', 'IN_STORAGE', 'DAMAGED'];

/** Badge colours per status. */
export const ASSET_STATUS_CLASSES: Record<AssetStatus, string> = {
  IN_USE: 'border-emerald-500/40 bg-emerald-500/10 text-emerald-300',
  UNDER_REPAIR: 'border-amber-500/40 bg-amber-500/10 text-amber-300',
  IN_STORAGE: 'border-sky-500/40 bg-sky-500/10 text-sky-300',
  DAMAGED: 'border-rose-500/40 bg-rose-500/10 text-rose-300',
  SOLD: 'border-line bg-white/5 text-slate-400',
  DISPOSED: 'border-line bg-white/5 text-slate-500',
};

export interface AssetCategory {
  id: number;
  name: string;
  active: boolean;
  assetCount: number;
}

export interface AssetFile {
  fileId: number;
  url: string;
  contentType: string;
  fileName?: string;
}

export interface AssetEvent {
  id: number;
  kind: AssetEventKind;
  date: string;
  description: string;
  cost?: number;
  createdBy?: { id: number; name: string };
  createdAt: string;
}

export interface Asset {
  id: number;
  name: string;
  category: { id: number; name: string };
  quantity: number;
  /** Absent for people who can't see costs (also totalCost, salePrice, repairTotal and the receipt). */
  unitCost?: number;
  totalCost?: number;
  purchaseDate: string;
  supplier?: string;
  paymentMethod?: PaymentMethod;
  reference?: string;
  location?: string;
  serialNumber?: string;
  warrantyUntil?: string;
  status: AssetStatus;
  statusLabel: string;
  disposedOn?: string;
  salePrice?: number;
  notes?: string;
  photo?: AssetFile;
  receipt?: AssetFile;
  repairTotal?: number;
  createdBy?: { id: number; name: string };
  createdAt: string;
  updatedAt: string;
  version: number;
  events: AssetEvent[];
}

export interface AssetSummary {
  /** Absent without "See costs and prices" (also repairSpent). */
  ownedValue?: number;
  ownedItems: number;
  ownedEntries: number;
  underRepair: number;
  warrantyEndingSoon: number;
  repairSpent?: number;
}

export interface AssetPage {
  content: Asset[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  filteredValue?: number;
  filteredItems: number;
  summary: AssetSummary;
}

export interface AssetQuery {
  q?: string;
  categoryId?: number;
  /** OWNED (default), ALL, or one status. */
  status?: string;
  location?: string;
  from?: string;
  to?: string;
  warranty?: 'SOON' | 'EXPIRED';
  page: number;
  size: number;
}

export interface AssetRequest {
  name: string;
  categoryId: number;
  quantity: number;
  /** Null for people who can't see costs (the server keeps or defaults it). */
  unitCost: number | null;
  purchaseDate: string;
  supplier: string | null;
  paymentMethod: PaymentMethod | null;
  reference: string | null;
  location: string | null;
  serialNumber: string | null;
  warrantyUntil: string | null;
  notes: string | null;
  photoFileId: number | null;
  receiptFileId: number | null;
  status?: AssetStatus;
  version?: number;
}

export interface UploadedFile {
  id: number;
  url: string;
  contentType: string;
  sizeBytes: number;
}
