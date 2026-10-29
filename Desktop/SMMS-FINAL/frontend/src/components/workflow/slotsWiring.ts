/**
 * COORDINATOR WIRING POINT. Imported (for side effects) by the work order and new-request pages.
 * After the materials and assets modules are merged, replace the body with:
 *
 *   import WorkOrderMaterialsPanel from '@/components/materials/WorkOrderMaterialsPanel';
 *   import AssetPicker from '@/components/assets/AssetPicker';
 *   registerWorkflowSlots({ MaterialsPanel: WorkOrderMaterialsPanel, AssetPicker });
 *
 * (use a named import if the files do not have default exports; their props must be compatible with
 * MaterialsPanelSlotProps / AssetPickerSlotProps in ./slots.ts). Left empty on purpose: those files
 * did not exist when this module was written and a missing import would fail the build.
 */
export {};
