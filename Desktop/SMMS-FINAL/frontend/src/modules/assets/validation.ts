import type { AssetFormValues } from './types';
import { todayIso } from './lib';

export type AssetErrors = Partial<Record<keyof AssetFormValues, string>>;

export const ASSET_LIMITS: Partial<Record<keyof AssetFormValues, number>> = {
  name: 200, nameAr: 200, assetCode: 60, category: 80, manufacturer: 120, model: 120, serialNumber: 120, location: 300,
};

type Msg = (key: 'vNameRequired' | 'vNameArRequired' | 'vCenterRequired' | 'vTooLong' | 'vInstallFuture' | 'vWarrantyBeforeInstall' | 'vDate', p?: Record<string, number>) => string;

const ISO = /^\d{4}-\d{2}-\d{2}$/;

/** Mirrors the server rules (CreateAssetRequest / UpdateAssetRequest); the server remains the authority. */
export function validateAsset(v: AssetFormValues, mode: 'create' | 'edit', msg: Msg): AssetErrors {
  const e: AssetErrors = {};
  if (mode === 'create' && !v.centerId) e.centerId = msg('vCenterRequired');
  if (!v.name.trim()) e.name = msg('vNameRequired');
  if (!v.nameAr.trim()) e.nameAr = msg('vNameArRequired');
  for (const [k, max] of Object.entries(ASSET_LIMITS) as [keyof AssetFormValues, number][]) {
    if (!e[k] && String(v[k]).trim().length > max) e[k] = msg('vTooLong', { n: max });
  }
  if (v.installDate && !ISO.test(v.installDate)) e.installDate = msg('vDate');
  if (v.warrantyExpiry && !ISO.test(v.warrantyExpiry)) e.warrantyExpiry = msg('vDate');
  if (!e.installDate && v.installDate && v.installDate > todayIso()) e.installDate = msg('vInstallFuture');
  if (!e.installDate && !e.warrantyExpiry && v.installDate && v.warrantyExpiry && v.warrantyExpiry < v.installDate) {
    e.warrantyExpiry = msg('vWarrantyBeforeInstall');
  }
  return e;
}
