import type { ModuleDict } from '@/lib/module-i18n';

const ar = {
  // common
  actions: 'إجراءات', active: 'نشط', inactive: 'غير نشط', activeOnly: 'النشطة فقط', inactiveOnly: 'غير النشطة فقط',
  allStatuses: 'كل الحالات', all: 'الكل', cancel: 'إلغاء', choose: 'اختر...', code: 'الرمز', date: 'التاريخ',
  edit: 'تعديل', filters: 'عوامل التصفية', from: 'من', to: 'إلى', name: 'الاسم', nameAr: 'الاسم بالعربية', nameEn: 'الاسم بالإنجليزية',
  note: 'ملاحظة', none: 'بدون', save: 'حفظ', saved: 'تم الحفظ', saving: 'جارٍ الحفظ...', search: 'بحث', status: 'الحالة',
  center: 'المركز', warehouse: 'المستودع', item: 'الصنف', quantity: 'الكمية', lines: 'البنود', addLine: 'إضافة بند',
  removeLine: 'حذف البند {n}', sections: 'أقسام المخزون', reason: 'السبب', reasonHint: 'من 5 إلى 1000 حرف',
  // validation
  errRequired: 'هذا الحقل مطلوب', errSku: 'أحرف وأرقام و . _ / - فقط', errCode: 'أحرف وأرقام و _ - فقط',
  errReorder: 'أدخل رقما بين 0 و 1000000', errRange: 'تاريخ البداية بعد تاريخ النهاية',
  errReason: 'اكتب سببا لا يقل عن 5 أحرف', errDuplicateItems: 'لا يمكن تكرار نفس الصنف في أكثر من بند',
  errPositive: 'أدخل كمية أكبر من صفر', errDecimals: 'الحد الأقصى 3 خانات عشرية', errMaxQty: 'الكمية تتجاوز 1,000,000',
  // items
  itemsTitle: 'الأصناف', itemsCount: '{n} صنف', searchItems: 'بحث بالاسم أو الرمز', stockLevel: 'مستوى المخزون',
  allStock: 'كل المستويات', lowOnly: 'منخفض المخزون فقط', sku: 'رمز الصنف (SKU)', skuImmutable: 'لا يمكن تغيير الرمز بعد الإنشاء',
  category: 'الفئة', uom: 'وحدة القياس', onHand: 'المتوفر فعليا', reserved: 'المحجوز', available: 'المتاح',
  reorderLevel: 'حد إعادة الطلب', lowStock: 'مخزون منخفض', noItems: 'لا توجد أصناف', noItemsHint: 'عدّل عوامل التصفية أو أضف صنفا جديدا.',
  newItem: 'صنف جديد', editItem: 'تعديل الصنف', itemNoStockHint: 'لا تُدخل كمية ابتدائية هنا: يدخل المخزون عبر سند الاستلام فقط.',
  itemCreated: 'تم حفظ الصنف: {name}', newReceipt: 'سند استلام', backToItems: 'العودة إلى الأصناف',
  balancesByWarehouse: 'الأرصدة حسب المستودع', noBalances: 'لا توجد أرصدة لهذا الصنف بعد.', minQty: 'الحد الأدنى', minQtyFor: 'الحد الأدنى للمستودع {warehouse}',
  stockCard: 'بطاقة الصنف', opening: 'الرصيد الافتتاحي', closing: 'الرصيد الختامي',
  // warehouses
  warehousesTitle: 'المستودعات', warehousesCount: '{n} مستودع', noWarehouses: 'لا توجد مستودعات', newWarehouse: 'مستودع جديد', editWarehouse: 'تعديل المستودع',
  // movements
  movementsTitle: 'حركات المخزون', movementsHint: 'سجل لا يُعدَّل: التصحيح بالتسوية أو العكس فقط.', filteredByItem: 'حركات صنف محدد',
  noMovements: 'لا توجد حركات', noMovementsHint: 'جرّب نطاق تواريخ آخر.', kind: 'النوع', balanceAfter: 'الرصيد بعد الحركة',
  references: 'المراجع', reasonNote: 'السبب / ملاحظة', actor: 'المنفّذ', workOrder: 'أمر العمل', materialRequest: 'طلب المواد',
  purchaseOrder: 'أمر الشراء', reversed: 'معكوسة', reverse: 'عكس', reverseTitle: 'عكس الحركة',
  reverseDesc: 'سيتم إنشاء حركة عكسية للحركة "{kind}" بكمية {qty} للصنف {item}.', reverseDone: 'تم عكس الحركة.',
  kind_opening: 'رصيد افتتاحي', kind_receipt: 'استلام', kind_issue: 'صرف', kind_return_in: 'إرجاع', kind_adjustment_in: 'تسوية بالزيادة',
  kind_adjustment_out: 'تسوية بالنقص', kind_reversal_in: 'عكس (زيادة)', kind_reversal_out: 'عكس (نقص)',
  // low stock
  lowStockTitle: 'تنبيهات انخفاض المخزون', lowStockHint: 'أصناف وصل مخزونها المتاح إلى حد إعادة الطلب أو أقل.',
  noLowStock: 'لا توجد تنبيهات', noLowStockHint: 'كل الأصناف فوق حد إعادة الطلب.',
  // receipt
  receiptTitle: 'سند استلام', receiptHint: 'استلام بضاعة بدون أمر شراء. يدخل المخزون إلى السجل بهذه الطريقة فقط.',
  reference: 'المرجع', referenceHint: 'رقم فاتورة أو إذن تسليم (اختياري)', chooseWarehouseFirst: 'اختر المستودع أولا',
  postReceipt: 'ترحيل الاستلام', receiptDone: 'تم ترحيل الاستلام ({n} حركة).',
  // adjustment
  adjustmentTitle: 'تسوية مخزون', adjustmentHint: 'تصحيح الكمية (للمدير فقط). السبب إلزامي ويُسجَّل في السجل.',
  direction: 'الاتجاه', dirIn: 'زيادة', dirOut: 'نقص', reasonCode: 'نوع السبب', postAdjustment: 'ترحيل التسوية',
  adjustmentDone: 'تمت التسوية بكمية {qty}. الرصيد الجديد: {balance}.',
  reason_count_correction: 'تصحيح جرد', reason_damage: 'تلف', reason_expired: 'منتهي الصلاحية', reason_found: 'زيادة عُثر عليها', reason_other: 'أخرى',
} as const;

const en: Partial<Record<keyof typeof ar, string>> = {
  actions: 'Actions', active: 'Active', inactive: 'Inactive', activeOnly: 'Active only', inactiveOnly: 'Inactive only',
  allStatuses: 'All statuses', all: 'All', cancel: 'Cancel', choose: 'Choose...', code: 'Code', date: 'Date',
  edit: 'Edit', filters: 'Filters', from: 'From', to: 'To', name: 'Name', nameAr: 'Arabic name', nameEn: 'English name',
  note: 'Note', none: 'None', save: 'Save', saved: 'Saved', saving: 'Saving...', search: 'Search', status: 'Status',
  center: 'Center', warehouse: 'Warehouse', item: 'Item', quantity: 'Quantity', lines: 'Lines', addLine: 'Add line',
  removeLine: 'Remove line {n}', sections: 'Inventory sections', reason: 'Reason', reasonHint: '5 to 1000 characters',
  errRequired: 'This field is required', errSku: 'Letters, digits and . _ / - only', errCode: 'Letters, digits and _ - only',
  errReorder: 'Enter a number between 0 and 1000000', errRange: 'Start date is after end date',
  errReason: 'Write a reason of at least 5 characters', errDuplicateItems: 'The same item cannot appear on more than one line',
  errPositive: 'Enter a quantity greater than zero', errDecimals: 'At most 3 decimals', errMaxQty: 'Quantity exceeds 1,000,000',
  itemsTitle: 'Items', itemsCount: '{n} items', searchItems: 'Search by name or SKU', stockLevel: 'Stock level',
  allStock: 'All levels', lowOnly: 'Low stock only', sku: 'SKU', skuImmutable: 'The SKU cannot be changed after creation',
  category: 'Category', uom: 'Unit of measure', onHand: 'On hand', reserved: 'Reserved', available: 'Available',
  reorderLevel: 'Reorder level', lowStock: 'Low stock', noItems: 'No items', noItemsHint: 'Adjust the filters or add a new item.',
  newItem: 'New item', editItem: 'Edit item', itemNoStockHint: 'Do not enter an opening quantity here: stock enters only through goods receipts.',
  itemCreated: 'Item saved: {name}', newReceipt: 'Goods receipt', backToItems: 'Back to items',
  balancesByWarehouse: 'Balances by warehouse', noBalances: 'This item has no balances yet.', minQty: 'Minimum quantity', minQtyFor: 'Minimum quantity for warehouse {warehouse}',
  stockCard: 'Stock card', opening: 'Opening balance', closing: 'Closing balance',
  warehousesTitle: 'Warehouses', warehousesCount: '{n} warehouses', noWarehouses: 'No warehouses', newWarehouse: 'New warehouse', editWarehouse: 'Edit warehouse',
  movementsTitle: 'Stock movements', movementsHint: 'Append-only ledger: correct with an adjustment or a reversal.', filteredByItem: 'Movements of one item',
  noMovements: 'No movements', noMovementsHint: 'Try another date range.', kind: 'Kind', balanceAfter: 'Balance after',
  references: 'References', reasonNote: 'Reason / note', actor: 'By', workOrder: 'Work order', materialRequest: 'Material request',
  purchaseOrder: 'Purchase order', reversed: 'Reversed', reverse: 'Reverse', reverseTitle: 'Reverse movement',
  reverseDesc: 'A reversing movement will be created for the "{kind}" movement of {qty} for {item}.', reverseDone: 'Movement reversed.',
  kind_opening: 'Opening balance', kind_receipt: 'Receipt', kind_issue: 'Issue', kind_return_in: 'Return', kind_adjustment_in: 'Adjustment in',
  kind_adjustment_out: 'Adjustment out', kind_reversal_in: 'Reversal (in)', kind_reversal_out: 'Reversal (out)',
  lowStockTitle: 'Low-stock alerts', lowStockHint: 'Items whose available stock is at or below the reorder level.',
  noLowStock: 'No alerts', noLowStockHint: 'Every item is above its reorder level.',
  receiptTitle: 'Goods receipt', receiptHint: 'Receive goods without a purchase order. This is the only way stock enters the ledger apart from PO receiving.',
  reference: 'Reference', referenceHint: 'Invoice or delivery note number (optional)', chooseWarehouseFirst: 'Choose a warehouse first',
  postReceipt: 'Post receipt', receiptDone: 'Receipt posted ({n} movements).',
  adjustmentTitle: 'Stock adjustment', adjustmentHint: 'Correct a quantity (managers only). A reason is mandatory and recorded in the ledger.',
  direction: 'Direction', dirIn: 'Increase', dirOut: 'Decrease', reasonCode: 'Reason type', postAdjustment: 'Post adjustment',
  adjustmentDone: 'Adjustment of {qty} posted. New balance: {balance}.',
  reason_count_correction: 'Count correction', reason_damage: 'Damage', reason_expired: 'Expired', reason_found: 'Found', reason_other: 'Other',
};

export type InventoryKey = keyof typeof ar;
export const inventoryDict: ModuleDict<InventoryKey> = { ar, en };
