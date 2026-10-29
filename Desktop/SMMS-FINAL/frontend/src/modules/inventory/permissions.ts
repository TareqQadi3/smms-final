import type { UserRole } from '@/lib/types';

// UI hints only. Every one of these is enforced again on the server (403/404 are always handled).
type R = UserRole | undefined;
const has = (role: R, list: UserRole[]) => !!role && list.includes(role);

export const can = {
  // inventory
  writeCatalog: (r: R) => has(r, ['system_admin', 'maintenance_manager', 'warehouse']),
  writeWarehouse: (r: R) => has(r, ['system_admin', 'maintenance_manager', 'hospital_supervisor']),
  receipt: (r: R) => has(r, ['system_admin', 'maintenance_manager', 'warehouse']),
  /** Adjustments and reversals: the warehouse role may not adjust. */
  correct: (r: R) => has(r, ['system_admin', 'maintenance_manager']),
  readStock: (r: R) =>
    has(r, [
      'system_admin',
      'region_supervisor',
      'hospital_supervisor',
      'maintenance_manager',
      'electrical_manager',
      'operating_company_manager',
      'warehouse',
    ]),
  readMovements: (r: R) =>
    has(r, [
      'system_admin',
      'region_supervisor',
      'hospital_supervisor',
      'maintenance_manager',
      'electrical_manager',
      'operating_company_manager',
      'warehouse',
      'technician',
    ]),
  // procurement
  readProcurement: (r: R) =>
    has(r, [
      'system_admin',
      'region_supervisor',
      'hospital_supervisor',
      'maintenance_manager',
      'operating_company_manager',
      'warehouse',
    ]),
  writeSupplier: (r: R) => has(r, ['system_admin', 'maintenance_manager', 'operating_company_manager']),
  editPo: (r: R) => has(r, ['warehouse', 'maintenance_manager']),
  decidePo: (r: R) => has(r, ['operating_company_manager', 'maintenance_manager', 'system_admin']),
  cancelPo: (r: R) => has(r, ['warehouse', 'maintenance_manager', 'operating_company_manager', 'system_admin']),
  receivePo: (r: R) => has(r, ['warehouse']),
  closePo: (r: R) => has(r, ['maintenance_manager', 'operating_company_manager']),
  // material requests
  readMr: (r: R) =>
    has(r, [
      'system_admin',
      'region_supervisor',
      'hospital_supervisor',
      'maintenance_manager',
      'electrical_manager',
      'operating_company_manager',
      'warehouse',
      'control_room',
      'technician',
    ]),
  createMr: (r: R) => has(r, ['technician', 'control_room', 'maintenance_manager']),
  decideMr: (r: R) => has(r, ['maintenance_manager', 'operating_company_manager', 'electrical_manager']),
  warehouseMr: (r: R) => has(r, ['warehouse']),
  consumeMr: (r: R) => has(r, ['technician']),
  cancelMr: (r: R) =>
    has(r, ['technician', 'control_room', 'maintenance_manager', 'warehouse', 'system_admin']),
  closeMr: (r: R) => has(r, ['warehouse', 'maintenance_manager']),
  /** Warehouses can be listed by these roles only (technicians and control room cannot). */
  listWarehouses: (r: R) => can.readStock(r),
};
