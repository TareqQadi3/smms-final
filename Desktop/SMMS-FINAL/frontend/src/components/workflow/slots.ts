import type { ComponentType } from 'react';

/**
 * EXTENSION POINTS for components written by other modules.
 *
 * The work order page needs two components it must not create itself:
 *   - components/materials/WorkOrderMaterialsPanel.tsx  (materials module)
 *   - components/assets/AssetPicker.tsx                  (assets module)
 * A static import of a file that does not exist would break the build, so the page reads them from
 * the registry below. The coordinator wires them in ONE place, `slotsWiring.ts` (two import lines),
 * after those modules are merged. Until then the page shows a short "not enabled" notice for the
 * materials panel and the new-request form uses the built-in `AssetSelectField`.
 */

/** Props the work order page passes to the materials panel. */
export interface MaterialsPanelSlotProps {
  workOrderId: string;
  /** Work order status (material requests are only possible in_progress / waiting_materials). */
  status: string;
  /** Id of the assigned technician, if any. */
  assignedToId?: string | null;
  /** Role of the signed-in user (UI hints only; the server enforces). */
  role?: string;
  /** Call after the panel changed something that affects the work order (e.g. needsMaterials). */
  onChanged?: () => void;
}

/** Props the new-request form passes to the asset picker. */
export interface AssetPickerSlotProps {
  /** Selected asset id or '' for none. */
  value: string;
  onChange: (assetId: string, asset?: { id: string; assetCode?: string | null; name: string; nameAr?: string | null }) => void;
  /** Center of the chosen control room; assets are listed for this center only. */
  centerId?: string;
  disabled?: boolean;
  label?: string;
}

export interface WorkflowSlots {
  MaterialsPanel: ComponentType<MaterialsPanelSlotProps> | null;
  AssetPicker: ComponentType<AssetPickerSlotProps> | null;
}

export const workflowSlots: WorkflowSlots = { MaterialsPanel: null, AssetPicker: null };

/** Register slot components (called from slotsWiring.ts). */
export function registerWorkflowSlots(partial: Partial<WorkflowSlots>) {
  Object.assign(workflowSlots, partial);
}
