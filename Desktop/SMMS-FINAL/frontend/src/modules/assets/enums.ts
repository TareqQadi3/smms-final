import type { ModuleDict } from '@/lib/module-i18n';

/** Labels of backend enums shared by the assets, PM and reports modules. */
const ar = {
  priority_critical: 'حرجة',
  priority_high: 'عالية',
  priority_medium: 'متوسطة',
  priority_low: 'منخفضة',
  subType_electrical: 'كهرباء',
  subType_plumbing: 'سباكة',
  subType_hvac: 'تكييف',
  subType_civil: 'مدني',
  subType_printer: 'طابعات',
  subType_network: 'شبكات',
  subType_software: 'برمجيات',
  subType_devices: 'أجهزة',
  subType_equipment_failure: 'عطل معدات',
  subType_preventive_maintenance: 'صيانة وقائية',
  subType_calibration: 'معايرة',
  crit_low: 'منخفضة',
  crit_medium: 'متوسطة',
  crit_high: 'عالية',
  crit_critical: 'حرجة',
  assetStatus_active: 'نشط',
  assetStatus_under_maintenance: 'تحت الصيانة',
  assetStatus_broken: 'معطل',
  freq_day: 'يوم',
  freq_week: 'أسبوع',
  freq_month: 'شهر',
  freq_year: 'سنة',
  runStatus_generated: 'تم الإنشاء',
  runStatus_skipped: 'تم التخطي',
  runStatus_failed: 'فشل',
  woStatus_new: 'جديد',
  woStatus_under_review: 'قيد المراجعة',
  woStatus_assigned: 'مُعيَّن',
  woStatus_in_progress: 'قيد التنفيذ',
  woStatus_waiting_materials: 'بانتظار المواد',
  woStatus_waiting_approval: 'بانتظار الموافقة',
  woStatus_completed: 'مكتمل',
  woStatus_closed: 'مغلق',
  woStatus_cancelled: 'ملغي',
} as const;

const en: Partial<Record<keyof typeof ar, string>> = {
  priority_critical: 'Critical',
  priority_high: 'High',
  priority_medium: 'Medium',
  priority_low: 'Low',
  subType_electrical: 'Electrical',
  subType_plumbing: 'Plumbing',
  subType_hvac: 'HVAC',
  subType_civil: 'Civil',
  subType_printer: 'Printers',
  subType_network: 'Network',
  subType_software: 'Software',
  subType_devices: 'Devices',
  subType_equipment_failure: 'Equipment failure',
  subType_preventive_maintenance: 'Preventive maintenance',
  subType_calibration: 'Calibration',
  crit_low: 'Low',
  crit_medium: 'Medium',
  crit_high: 'High',
  crit_critical: 'Critical',
  assetStatus_active: 'Active',
  assetStatus_under_maintenance: 'Under maintenance',
  assetStatus_broken: 'Broken',
  freq_day: 'day',
  freq_week: 'week',
  freq_month: 'month',
  freq_year: 'year',
  runStatus_generated: 'Generated',
  runStatus_skipped: 'Skipped',
  runStatus_failed: 'Failed',
  woStatus_new: 'New',
  woStatus_under_review: 'Under review',
  woStatus_assigned: 'Assigned',
  woStatus_in_progress: 'In progress',
  woStatus_waiting_materials: 'Waiting for materials',
  woStatus_waiting_approval: 'Waiting for approval',
  woStatus_completed: 'Completed',
  woStatus_closed: 'Closed',
  woStatus_cancelled: 'Cancelled',
};

export type EnumKey = keyof typeof ar;
export const enumDict: ModuleDict<EnumKey> = { ar, en };

export const SUB_TYPES = [
  'electrical', 'plumbing', 'hvac', 'civil', 'printer', 'network', 'software', 'devices',
  'equipment_failure', 'preventive_maintenance', 'calibration',
] as const;
export const PRIORITIES = ['critical', 'high', 'medium', 'low'] as const;
export const CRITICALITIES = ['low', 'medium', 'high', 'critical'] as const;
export const ASSET_STATUSES = ['active', 'under_maintenance', 'broken'] as const;
export const FREQUENCY_UNITS = ['day', 'week', 'month', 'year'] as const;

/** Label for `prefix_value`, falling back to the raw value for codes the dictionary does not know. */
export function enumLabel(t: (key: EnumKey) => string, prefix: string, value: string | null | undefined): string {
  if (!value) return '-';
  const key = `${prefix}_${value}` as EnumKey;
  const out = t(key);
  return out === key ? value : out;
}
