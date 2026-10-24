export type FrequencyUnit = 'day' | 'week' | 'month' | 'year';

export interface PlanRun {
  id: string;
  dueDate: string;
  status: 'generated' | 'skipped' | 'failed';
  source: string;
  workOrderId: string | null;
  error: string | null;
  overdueNotifiedAt: string | null;
  createdAt: string;
}

export interface Plan {
  id: string;
  assetId: string;
  assetCode: string | null;
  assetName: string;
  centerId: string;
  controlRoomId: string;
  subType: string;
  priority: string | null;
  title: string;
  instructions: string | null;
  frequencyUnit: FrequencyUnit;
  frequencyInterval: number;
  leadDays: number;
  startDate: string;
  nextDueDate: string;
  active: boolean;
  ownerId: string | null;
  ownerName: string | null;
  overdue: boolean;
  version: number;
  lastRuns?: PlanRun[];
}

export interface PlanListParams {
  centerId?: string;
  assetId?: string;
  active?: boolean | '';
  overdue?: boolean;
  page?: number;
  size?: number;
}

export interface PlanFormValues {
  assetId: string;
  centerId: string;
  controlRoomId: string;
  subType: string;
  priority: string;
  title: string;
  instructions: string;
  frequencyUnit: FrequencyUnit;
  frequencyInterval: string;
  leadDays: string;
  startDate: string;
  nextDueDate: string;
}

export interface GenerateResult {
  planId: string;
  dueDate: string;
  workOrderId: string;
  nextDueDate: string;
}

export interface ControlRoomLite {
  id: string;
  name: string;
  nameAr: string;
  code?: string;
  active?: boolean;
}
