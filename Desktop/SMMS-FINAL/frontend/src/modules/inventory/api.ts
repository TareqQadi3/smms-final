import { api } from '@/lib/api';
import { idempotencyHeaders } from './idempotency';
import type {
  AdjustmentRequest,
  InventoryItem,
  ItemInput,
  Movement,
  Page,
  ReceiptRequest,
  StockCard,
  Uom,
  Warehouse,
  WarehouseInput,
} from './types';

type Params = Record<string, string | number | boolean | undefined | null>;
const clean = (p?: Params) =>
  Object.fromEntries(Object.entries(p ?? {}).filter(([, v]) => v !== undefined && v !== null && v !== ''));

export const inventoryApi = {
  listItems: (p?: Params) => api.get<Page<InventoryItem>>('/inventory/items', { params: clean(p) }).then((r) => r.data),
  getItem: (id: string) => api.get<InventoryItem>(`/inventory/items/${id}`).then((r) => r.data),
  createItem: (b: ItemInput) => api.post<InventoryItem>('/inventory/items', b).then((r) => r.data),
  updateItem: (id: string, b: ItemInput) => api.put<InventoryItem>(`/inventory/items/${id}`, b).then((r) => r.data),
  stockCard: (id: string, p: Params) =>
    api.get<StockCard>(`/inventory/items/${id}/stock-card`, { params: clean(p) }).then((r) => r.data),
  setMinQty: (b: { warehouseId: string; itemId: string; minQty: number }) =>
    api.put('/inventory/balances/min-qty', b).then((r) => r.data),
  lowStock: (p?: Params) =>
    api.get<Page<InventoryItem>>('/inventory/alerts/low-stock', { params: clean(p) }).then((r) => r.data),
  listWarehouses: (p?: Params) =>
    api.get<Page<Warehouse>>('/inventory/warehouses', { params: clean(p) }).then((r) => r.data),
  createWarehouse: (b: WarehouseInput) => api.post<Warehouse>('/inventory/warehouses', b).then((r) => r.data),
  updateWarehouse: (id: string, b: WarehouseInput) =>
    api.put<Warehouse>(`/inventory/warehouses/${id}`, b).then((r) => r.data),
  listUoms: (p?: Params) => api.get<Page<Uom>>('/inventory/uoms', { params: clean(p) }).then((r) => r.data),
  listMovements: (p?: Params) =>
    api.get<Page<Movement>>('/inventory/movements', { params: clean(p) }).then((r) => r.data),
  receipt: (b: ReceiptRequest, key: string) =>
    api.post<{ movements: Movement[] }>('/inventory/receipts', b, idempotencyHeaders(key)).then((r) => r.data),
  adjust: (b: AdjustmentRequest, key: string) =>
    api.post<Movement>('/inventory/adjustments', b, idempotencyHeaders(key)).then((r) => r.data),
  reverse: (id: string, reason: string, key: string) =>
    api.post<Movement>(`/inventory/movements/${id}/reverse`, { reason }, idempotencyHeaders(key)).then((r) => r.data),
};
