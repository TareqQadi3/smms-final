import type { UserRole } from '@/lib/types';

/** UI hints only: the server enforces every one of these (403 / 404). */
export const ASSET_READ: UserRole[] = [
  'system_admin', 'region_supervisor', 'hospital_supervisor', 'maintenance_manager', 'electrical_manager',
  'operating_company_manager', 'control_room', 'warehouse', 'technician', 'department',
];
export const ASSET_WRITE: UserRole[] = ['system_admin', 'hospital_supervisor', 'maintenance_manager', 'electrical_manager'];
export const ASSET_MATERIALS: UserRole[] = [
  'system_admin', 'region_supervisor', 'hospital_supervisor', 'maintenance_manager', 'electrical_manager',
  'operating_company_manager', 'warehouse', 'technician',
];
export const DOWNTIME_WRITE: UserRole[] = [
  'system_admin', 'hospital_supervisor', 'maintenance_manager', 'electrical_manager', 'control_room', 'technician',
];
export const PM_READ: UserRole[] = [
  'system_admin', 'region_supervisor', 'hospital_supervisor', 'maintenance_manager', 'electrical_manager',
  'operating_company_manager', 'control_room',
];
export const PM_WRITE: UserRole[] = ASSET_WRITE;
export const DEPT_READ: UserRole[] = PM_READ;
export const DEPT_WRITE: UserRole[] = ['system_admin', 'region_supervisor', 'hospital_supervisor'];
export const REPORT_READ: UserRole[] = [
  'system_admin', 'region_supervisor', 'hospital_supervisor', 'maintenance_manager', 'electrical_manager',
  'operating_company_manager',
];
export const REPORT_INVENTORY: UserRole[] = [...REPORT_READ, 'warehouse'];

export function hasRole(role: UserRole | undefined | null, allowed: UserRole[]): boolean {
  return !!role && allowed.includes(role);
}
