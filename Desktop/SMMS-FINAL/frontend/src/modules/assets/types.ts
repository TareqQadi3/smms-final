export type AssetStatus = 'active' | 'under_maintenance' | 'broken';
export type Criticality = 'low' | 'medium' | 'high' | 'critical';

export interface CenterRef {
  id: string;
  name: string;
  nameAr: string;
  code?: string;
}

export interface DepartmentRef {
  id: string;
  name: string;
  nameAr: string;
  code?: string;
  active?: boolean;
  center?: CenterRef;
}

export interface Asset {
  id: string;
  assetCode: string | null;
  name: string;
  nameAr: string;
  category: string | null;
  manufacturer: string | null;
  model: string | null;
  serialNumber: string | null;
  location: string | null;
  status: AssetStatus;
  criticality: Criticality | null;
  installDate: string | null;
  warrantyExpiry: string | null;
  decommissionedAt: string | null;
  decommissionReason?: string | null;
  center: CenterRef;
  department?: DepartmentRef | null;
  openWorkOrders: number;
  activeDowntime: boolean;
  version: number;
}

/** What AssetPicker hands back (a subset of Asset). */
export type AssetOption = Pick<Asset, 'id' | 'assetCode' | 'name' | 'nameAr' | 'center'>;

export interface AssetListParams {
  centerId?: string;
  departmentId?: string;
  status?: AssetStatus | '';
  category?: string;
  q?: string;
  includeDecommissioned?: boolean;
  page?: number;
  size?: number;
  sort?: string;
}

export interface AssetFormValues {
  centerId: string;
  departmentId: string;
  name: string;
  nameAr: string;
  assetCode: string;
  category: string;
  manufacturer: string;
  model: string;
  serialNumber: string;
  location: string;
  installDate: string;
  warrantyExpiry: string;
  criticality: Criticality | '';
  status: AssetStatus;
}

export interface AssetWorkOrderRow {
  id: string;
  ticketNumber: string;
  title: string;
  status: string;
  priority: string;
  subType: string;
  preventive: boolean;
  planId: string | null;
  createdAt: string;
  completedDate: string | null;
  closedAt: string | null;
  slaBreached: boolean;
  techReport: string | null;
  assignedToId: string | null;
  assignedToName: string | null;
}

export interface MaterialRow {
  movementId: string;
  workOrderId: string;
  itemId: string;
  sku: string | null;
  itemName: string;
  itemNameAr: string | null;
  quantity: number | string;
  kind: string;
  createdAt: string;
}

export interface Downtime {
  id: string;
  assetId: string;
  workOrderId: string | null;
  startedAt: string;
  endedAt: string | null;
  reason: string;
  recordedById: string | null;
  endedById: string | null;
  durationSeconds: number | null;
}
