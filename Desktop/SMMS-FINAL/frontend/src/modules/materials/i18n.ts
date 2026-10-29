import type { ModuleDict } from '@/lib/module-i18n';

const ar = {
  actions: 'إجراءات', addLine: 'إضافة بند', all: 'الكل', back: 'رجوع', cancel: 'إلغاء', center: 'المركز', choose: 'اختر...', item: 'الصنف',
  lines: 'البنود', note: 'ملاحظة', noteOptional: 'ملاحظة (اختياري)', quantity: 'الكمية', reason: 'السبب', removeLine: 'حذف البند {n}',
  saving: 'جارٍ الحفظ...', working: 'جارٍ التنفيذ...', status: 'الحالة', supplier: 'المورد', warehouse: 'المستودع', workOrder: 'أمر العمل',
  createdAt: 'تاريخ الإنشاء',
  errRequired: 'هذا الحقل مطلوب', errPositive: 'أدخل كمية أكبر من صفر', errDecimals: 'الحد الأقصى 3 خانات عشرية',
  errMaxQty: 'الكمية تتجاوز 1,000,000', errDuplicateItems: 'لا يمكن تكرار نفس الصنف في أكثر من بند', errUuid: 'معرّف المستودع غير صالح',
  // list
  mrTitle: 'طلبات المواد', mrHint: 'طلبات قطع الغيار والمواد لأوامر العمل', mrCount: '{n} طلب', noMr: 'لا توجد طلبات مواد',
  noMrHint: 'تُنشأ الطلبات من صفحة أمر العمل.', requestNumber: 'رقم الطلب', requestedBy: 'مقدّم الطلب',
  // detail
  decidedBy: 'القرار بواسطة', decisionNote: 'ملاحظة القرار', cancelReason: 'سبب الإلغاء', qtyRequested: 'المطلوب', qtyApproved: 'المعتمد',
  qtyReserved: 'المحجوز', qtyIssued: 'المصروف', qtyConsumed: 'المستهلك', qtyReturned: 'المرتجع',
  approve: 'اعتماد', approveDesc: 'حدد الكمية المعتمدة لكل بند. البند الفارغ يُعتمد بصفر. يجب اعتماد كمية واحدة على الأقل.',
  reject: 'رفض', reserve: 'حجز', reserveDesc: 'حجز الكميات المعتمدة من رصيد المستودع.', issue: 'صرف', issueDesc: 'أدخل الكمية المصروفة لكل بند (الحد الأقصى: المعتمد ناقص المصروف).',
  consume: 'تسجيل الاستهلاك', consumeDesc: 'سجّل الكميات التي استُهلكت فعلا في أمر العمل.', giveBack: 'إرجاع للمستودع',
  returnDesc: 'سجّل الكميات المرتجعة من الفني إلى المستودع. السبب إلزامي.', closeMr: 'إغلاق الطلب',
  closeDesc: 'إغلاق الطلب المصروف وتحرير أي حجوزات مفتوحة. لا يمكن الإغلاق وهناك مواد غير مستهلكة أو مرتجعة.',
  cancelMr: 'إلغاء الطلب', createPo: 'إنشاء أمر شراء', createPoDesc: 'ينشئ مسودة أمر شراء للكميات غير المتوفرة من الطلب المعتمد.',
  doneApprove: 'تم اعتماد الطلب.', doneReject: 'تم رفض الطلب.', doneReserve: 'تم حجز المواد.', doneIssue: 'تم صرف المواد.',
  doneConsume: 'تم تسجيل الاستهلاك.', doneReturn: 'تم تسجيل الإرجاع.', doneClose: 'تم إغلاق الطلب.', doneCancel: 'تم إلغاء الطلب.',
  status_submitted: 'بانتظار الاعتماد', status_approved: 'معتمد', status_rejected: 'مرفوض', status_reserved: 'محجوز', status_issued: 'مصروف',
  status_closed: 'مغلق', status_cancelled: 'ملغي',
  // panel / create
  materialsPanelTitle: 'المواد وقطع الغيار', noMrForWo: 'لا توجد طلبات مواد لهذا الأمر.', newMr: 'طلب مواد جديد',
  newMrDesc: 'اختر المستودع والأصناف المطلوبة. يتطلب أن يكون أمر العمل قيد التنفيذ أو بانتظار المواد.', submitRequest: 'إرسال الطلب',
  mrCreated: 'تم إنشاء الطلب {n}.', mrNeedsActiveWo: 'يمكن طلب المواد فقط عندما يكون أمر العمل قيد التنفيذ أو بانتظار المواد.',
  warehouseId: 'معرّف المستودع', warehouseIdHint: 'قائمة المستودعات غير متاحة لدورك. اطلب المعرّف من أمين المستودع.',
  sameWarehouse: 'نفس مستودع الطلب السابق',
} as const;

const en: Partial<Record<keyof typeof ar, string>> = {
  actions: 'Actions', addLine: 'Add line', all: 'All', back: 'Back', cancel: 'Cancel', center: 'Center', choose: 'Choose...', item: 'Item',
  lines: 'Lines', note: 'Note', noteOptional: 'Note (optional)', quantity: 'Quantity', reason: 'Reason', removeLine: 'Remove line {n}',
  saving: 'Saving...', working: 'Working...', status: 'Status', supplier: 'Supplier', warehouse: 'Warehouse', workOrder: 'Work order',
  createdAt: 'Created',
  errRequired: 'This field is required', errPositive: 'Enter a quantity greater than zero', errDecimals: 'At most 3 decimals',
  errMaxQty: 'Quantity exceeds 1,000,000', errDuplicateItems: 'The same item cannot appear on more than one line', errUuid: 'Invalid warehouse id',
  mrTitle: 'Material requests', mrHint: 'Spare parts and material requests for work orders', mrCount: '{n} requests', noMr: 'No material requests',
  noMrHint: 'Requests are created from the work order page.', requestNumber: 'Request number', requestedBy: 'Requested by',
  decidedBy: 'Decided by', decisionNote: 'Decision note', cancelReason: 'Cancel reason', qtyRequested: 'Requested', qtyApproved: 'Approved',
  qtyReserved: 'Reserved', qtyIssued: 'Issued', qtyConsumed: 'Consumed', qtyReturned: 'Returned',
  approve: 'Approve', approveDesc: 'Set the approved quantity per line. A blank line is approved with zero. At least one quantity must be approved.',
  reject: 'Reject', reserve: 'Reserve', reserveDesc: 'Reserve the approved quantities from warehouse stock.', issue: 'Issue', issueDesc: 'Enter the issued quantity per line (maximum: approved minus issued).',
  consume: 'Record consumption', consumeDesc: 'Record the quantities actually used on the work order.', giveBack: 'Return to warehouse',
  returnDesc: 'Record quantities returned from the technician to the warehouse. A reason is required.', closeMr: 'Close request',
  closeDesc: 'Close the issued request and release open reservations. Not possible while materials are neither consumed nor returned.',
  cancelMr: 'Cancel request', createPo: 'Create purchase order', createPoDesc: 'Creates a draft purchase order for the quantities of the approved request that are not in stock.',
  doneApprove: 'Request approved.', doneReject: 'Request rejected.', doneReserve: 'Materials reserved.', doneIssue: 'Materials issued.',
  doneConsume: 'Consumption recorded.', doneReturn: 'Return recorded.', doneClose: 'Request closed.', doneCancel: 'Request cancelled.',
  status_submitted: 'Awaiting approval', status_approved: 'Approved', status_rejected: 'Rejected', status_reserved: 'Reserved', status_issued: 'Issued',
  status_closed: 'Closed', status_cancelled: 'Cancelled',
  materialsPanelTitle: 'Materials and spare parts', noMrForWo: 'No material requests for this work order.', newMr: 'New material request',
  newMrDesc: 'Choose the warehouse and the items. The work order must be in progress or waiting for materials.', submitRequest: 'Submit request',
  mrCreated: 'Request {n} created.', mrNeedsActiveWo: 'Materials can only be requested while the work order is in progress or waiting for materials.',
  warehouseId: 'Warehouse id', warehouseIdHint: 'The warehouse list is not available for your role. Ask the storekeeper for the id.',
  sameWarehouse: 'Same warehouse as the earlier request',
};

export type MaterialsKey = keyof typeof ar;
export const materialsDict: ModuleDict<MaterialsKey> = { ar, en };
