'use client';

/**
 * Wiring point: registers the real materials panel and asset picker for the work-order and
 * new-request pages (imported for its side effects by those pages). The picker is controlled by an
 * AssetOption, the slot contract by an id string, so a thin adapter keeps the selection locally.
 */
import { useEffect, useState } from 'react';
import { registerWorkflowSlots, type AssetPickerSlotProps } from './slots';
import { WorkOrderMaterialsPanel } from '@/components/materials/WorkOrderMaterialsPanel';
import { AssetPicker, type AssetOption } from '@/components/assets/AssetPicker';

function AssetPickerSlot({ value, onChange, centerId, disabled, label }: AssetPickerSlotProps) {
  const [selected, setSelected] = useState<AssetOption | null>(null);

  // The parent clearing the id (or switching control room) clears the visible selection too.
  useEffect(() => {
    if (!value) setSelected(null);
  }, [value]);

  return (
    <AssetPicker
      value={selected}
      centerId={centerId}
      disabled={disabled}
      label={label}
      onChange={(asset) => {
        setSelected(asset);
        onChange(asset ? asset.id : '', asset ?? undefined);
      }}
    />
  );
}

registerWorkflowSlots({ MaterialsPanel: WorkOrderMaterialsPanel, AssetPicker: AssetPickerSlot });
