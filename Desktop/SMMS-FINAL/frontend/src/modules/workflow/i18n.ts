import type { ModuleDict } from '@/lib/module-i18n';

const ar = {
  // generic
  retry: 'إعادة المحاولة', reload: 'إعادة التحميل', cancel: 'إلغاء', confirm: 'تأكيد', working: 'جارٍ التنفيذ...',
  reason: 'السبب', reasonHint: 'من 5 إلى 1000 حرف', reasonTooShort: 'اكتب سببًا لا يقل عن 5 أحرف',
  loading: 'جارٍ التحميل...', none: 'لا يوجد', by: 'بواسطة', at: 'في',
  errForbidden: 'ليست لديك صلاحية لتنفيذ هذا الإجراء', errNotFound: 'العنصر غير موجود أو خارج نطاق صلاحيتك',
  errGeneric: 'حدث خطأ، حاول مرة أخرى', loadFailed: 'تعذّر تحميل البيانات',
  conflictReload: 'تم تعديل هذا البلاغ من مستخدم آخر في نفس الوقت. أعد تحميل الصفحة ثم حاول مرة أخرى.',
  conflictTitle: 'تعارض في التعديل',
  // emergency
  emergency: 'طوارئ', emergencyActive: 'حالة طوارئ', emergencyDeclaredAt: 'أُعلنت في',
  declareEmergency: 'إعلان طوارئ', clearEmergency: 'إلغاء حالة الطوارئ',
  declareEmergencyTitle: 'إعلان حالة طوارئ',
  declareEmergencyDesc: 'ستصبح الأولوية حرجة وسيتم تقصير مهلة الاتفاقية عند الحاجة وإشعار المسؤولين. لا يمكن التراجع عن تقصير المهلة.',
  clearEmergencyTitle: 'إلغاء حالة الطوارئ',
  clearEmergencyDesc: 'لن تتغير الأولوية ولا مهلة الاتفاقية عند الإلغاء. سيُسجَّل السبب في السجل.',
  emergencyDeclared: 'تم إعلان حالة الطوارئ', emergencyCleared: 'تم إلغاء حالة الطوارئ',
  // escalation
  escalation: 'التصعيد', escalationLevel: 'المستوى {n}', escalateNow: 'تصعيد يدوي',
  escalateTitle: 'تصعيد البلاغ للمستوى التالي', escalateDesc: 'سيتم إشعار المسؤولين بالمستوى التالي (الحد الأقصى 3).',
  escalated: 'تم تصعيد البلاغ', noEscalations: 'لا توجد عمليات تصعيد لهذا البلاغ',
  escalationMax: 'بلغ البلاغ أعلى مستوى للتصعيد', escalationPaused: 'لا يمكن التصعيد أثناء إيقاف مهلة الاتفاقية',
  trigger_sla_breach: 'تجاوز مهلة الاتفاقية', trigger_sla_overdue: 'تجاوز ممتد للمهلة', trigger_legacy_breach: 'تجاوز سابق للمهلة', trigger_manual: 'يدوي', trigger_emergency: 'طوارئ',
  triggerBy: 'بواسطة {name}', notifiedCount: 'تم إشعار {n}',
  // info thread
  infoThread: 'طلبات المعلومات', noInfoRequests: 'لا توجد طلبات معلومات',
  infoAsked: 'السؤال', infoAnswer: 'الجواب', infoWaiting: 'بانتظار جواب مقدم البلاغ', infoCancelled: 'أُلغي الطلب',
  infoPausedFor: 'مدة الإيقاف: {d}', slaPausedBadge: 'مهلة الاتفاقية متوقفة مؤقتًا',
  slaPausedTotal: 'إجمالي مدة الإيقاف: {d}',
  requestInfo: 'طلب معلومات إضافية', provideInfo: 'تقديم المعلومات المطلوبة',
  infoAwaiting: 'بانتظار معلومات من مقدم البلاغ',
  // rating / confirmation
  confirmation: 'تأكيد مقدم البلاغ', confirmAccepted: 'قبل مقدم البلاغ الإنجاز', confirmDisputed: 'اعترض مقدم البلاغ على الإنجاز',
  confirmPending: 'بانتظار تأكيد مقدم البلاغ', confirmedAt: 'تاريخ التأكيد', rating: 'تقييم الخدمة',
  noRating: 'لم يتم تقييم هذا البلاغ بعد', ratingOutOf: '{n} من 5',
  // asset
  asset: 'الأصل', assetLink: 'فتح سجل الأصل', noAsset: 'غير مرتبط بأصل',
  assetOptional: 'الأصل (اختياري)', assetNone: '— بدون أصل —', assetSearch: 'ابحث بالاسم أو الرمز',
  assetsLoadFailed: 'تعذّر تحميل الأصول، يمكنك إرسال البلاغ بدونها', assetsNoResults: 'لا توجد أصول مطابقة',
  assetsNeedRoom: 'اختر غرفة التحكم أولاً لعرض أصول المركز',
  // attachments
  attachments: 'المرفقات', noAttachments: 'لا توجد مرفقات', download: 'تنزيل', downloadFailed: 'تعذّر تنزيل الملف',
  photos: 'صور قبل وبعد', photosBefore: 'قبل', photosAfter: 'بعد', photosOther: 'مرفقات عامة', noPhotos: 'لا توجد صور',
  photoUnavailable: 'تعذّر عرض الصورة',
  // notes / history
  notes: 'الملاحظات', addNote: 'إضافة ملاحظة', noteLabel: 'نص الملاحظة', notePlaceholder: 'اكتب ملاحظة تظهر في سجل البلاغ',
  noteAdded: 'تمت إضافة الملاحظة', noteTooLong: 'الحد الأقصى 2000 حرف', noteRequired: 'اكتب الملاحظة', saveNote: 'حفظ الملاحظة',
  noteBadge: 'ملاحظة', history: 'السجل',
  // materials slot
  materials: 'المواد المستخدمة', materialsUnavailable: 'وحدة المواد غير مفعّلة بعد',
  // approvals
  approvalsReadOnly: 'عرض فقط: قرارات الموافقة متاحة لمدير شركة التشغيل.',
  approvalNoteRequired: 'الملاحظة مطلوبة عند الرفض', approvalDone: 'تم تسجيل القرار',
  // new request
  emergencyAtCreate: 'بلاغ طارئ', emergencyAtCreateHint: 'سيتم رفع الأولوية إلى حرجة وإشعار المسؤولين بعد إنشاء البلاغ',
  emergencyReasonLabel: 'سبب الطوارئ', createdButEmergencyFailed: 'تم إنشاء البلاغ لكن تعذّر إعلان الطوارئ: {m}. يمكنك إعلانها من صفحة البلاغ.',
  // notification settings
  prefsTitle: 'إعدادات الإشعارات', prefsSubtitle: 'الإشعارات داخل النظام مفعّلة دائمًا. اختر القنوات الخارجية الإضافية.',
  channelEmail: 'البريد الإلكتروني', channelSms: 'الرسائل النصية',
  channelOn: 'استلام الإشعارات عبر {c}',
  notAvailableEmail: 'مزود البريد غير مهيأ في هذا النظام، لذلك لن تُرسل رسائل بريد حتى يهيئه مدير النظام.',
  notAvailableSms: 'مزود الرسائل النصية غير مهيأ في هذا النظام، لذلك لن تُرسل رسائل نصية حتى يهيئه مدير النظام.',
  availableEmail: 'مزود البريد جاهز', availableSms: 'مزود الرسائل النصية جاهز',
  savePrefs: 'حفظ الإعدادات', prefsSaved: 'تم حفظ الإعدادات', inAppAlways: 'الإشعارات داخل التطبيق: مفعّلة دائمًا',
  prefsHonest: 'ملاحظة: حالة "تم الإرسال" تعني أن المزود قبل الرسالة وليس أنها وصلت أو قُرئت.',
  // deliveries
  deliveriesTitle: 'سجل إرسال الإشعارات', deliveriesSubtitle: 'للقراءة فقط. لا تُعرض الأسرار ولا العناوين الكاملة.',
  providers: 'حالة المزودين', providerEnabled: 'مفعّل', providerDisabled: 'غير مفعّل', providerConfigured: 'مهيأ',
  providerNotConfigured: 'غير مهيأ', providerProblems: 'ملاحظات',
  filterStatus: 'الحالة', filterChannel: 'القناة', filterFrom: 'من تاريخ', filterTo: 'إلى تاريخ', filterAll: 'الكل',
  applyFilters: 'تطبيق', resetFilters: 'مسح', noDeliveries: 'لا توجد سجلات مطابقة',
  colChannel: 'القناة', colDestination: 'الوجهة', colStatus: 'الحالة', colAttempts: 'المحاولات', colProvider: 'المزود',
  colCreated: 'أُنشئ', colSent: 'أُرسل', colError: 'آخر خطأ', totalRows: '{n} سجل', invalidRange: 'تاريخ البداية بعد تاريخ النهاية',
  status_pending: 'قيد الانتظار', status_failed: 'فشل (سيُعاد)', status_sent: 'قبله المزود', status_dead: 'فشل نهائي',
  status_not_configured: 'مزود غير مهيأ', status_skipped_no_address: 'لا يوجد عنوان',
  // permission
  adminOnly: 'هذه الصفحة لمدير النظام فقط',
} as const;

export type WorkflowKey = keyof typeof ar;

const en: Partial<Record<WorkflowKey, string>> = {
  retry: 'Retry', reload: 'Reload', cancel: 'Cancel', confirm: 'Confirm', working: 'Working...',
  reason: 'Reason', reasonHint: '5 to 1000 characters', reasonTooShort: 'Enter a reason of at least 5 characters',
  loading: 'Loading...', none: 'None', by: 'by', at: 'at',
  errForbidden: 'You do not have permission to perform this action', errNotFound: 'Not found or outside your scope',
  errGeneric: 'Something went wrong, please try again', loadFailed: 'Failed to load data',
  conflictReload: 'This work order was changed by someone else at the same time. Reload and try again.',
  conflictTitle: 'Edit conflict',
  emergency: 'Emergency', emergencyActive: 'Emergency', emergencyDeclaredAt: 'Declared at',
  declareEmergency: 'Declare emergency', clearEmergency: 'Clear emergency',
  declareEmergencyTitle: 'Declare an emergency',
  declareEmergencyDesc: 'Priority becomes critical, the SLA deadline is shortened when needed and managers are notified. A shortened deadline is not restored.',
  clearEmergencyTitle: 'Clear the emergency',
  clearEmergencyDesc: 'Priority and the SLA deadline stay as they are. The reason is recorded in the history.',
  emergencyDeclared: 'Emergency declared', emergencyCleared: 'Emergency cleared',
  escalation: 'Escalation', escalationLevel: 'Level {n}', escalateNow: 'Escalate manually',
  escalateTitle: 'Escalate to the next level', escalateDesc: 'The next level of managers is notified (maximum level 3).',
  escalated: 'Work order escalated', noEscalations: 'No escalations for this work order',
  escalationMax: 'Highest escalation level reached', escalationPaused: 'Cannot escalate while the SLA is paused',
  trigger_sla_breach: 'SLA breached', trigger_sla_overdue: 'SLA overrun', trigger_legacy_breach: 'Earlier SLA breach', trigger_manual: 'Manual', trigger_emergency: 'Emergency',
  triggerBy: 'by {name}', notifiedCount: '{n} notified',
  infoThread: 'Information requests', noInfoRequests: 'No information requests',
  infoAsked: 'Question', infoAnswer: 'Answer', infoWaiting: 'Waiting for the requester', infoCancelled: 'Request cancelled',
  infoPausedFor: 'Paused for: {d}', slaPausedBadge: 'SLA clock paused',
  slaPausedTotal: 'Total pause: {d}',
  requestInfo: 'Request Information', provideInfo: 'Provide Information',
  infoAwaiting: 'Awaiting requester information',
  confirmation: 'Requester confirmation', confirmAccepted: 'The requester accepted the work', confirmDisputed: 'The requester disputed the work',
  confirmPending: 'Waiting for requester confirmation', confirmedAt: 'Confirmed at', rating: 'Service rating',
  noRating: 'This work order has not been rated yet', ratingOutOf: '{n} out of 5',
  asset: 'Asset', assetLink: 'Open asset record', noAsset: 'Not linked to an asset',
  assetOptional: 'Asset (optional)', assetNone: '— No asset —', assetSearch: 'Search by name or code',
  assetsLoadFailed: 'Could not load assets; you can submit without one', assetsNoResults: 'No matching assets',
  assetsNeedRoom: 'Select a control room first to list the center assets',
  attachments: 'Attachments', noAttachments: 'No attachments', download: 'Download', downloadFailed: 'Download failed',
  photos: 'Before and after photos', photosBefore: 'Before', photosAfter: 'After', photosOther: 'General attachments', noPhotos: 'No photos',
  photoUnavailable: 'Photo could not be displayed',
  notes: 'Notes', addNote: 'Add note', noteLabel: 'Note text', notePlaceholder: 'Write a note that appears in the work order history',
  noteAdded: 'Note added', noteTooLong: 'Maximum 2000 characters', noteRequired: 'Enter the note', saveNote: 'Save note',
  noteBadge: 'Note', history: 'History',
  materials: 'Materials used', materialsUnavailable: 'The materials module is not enabled yet',
  approvalsReadOnly: 'Read only: approval decisions are available to the operating company manager.',
  approvalNoteRequired: 'A note is required when rejecting', approvalDone: 'Decision recorded',
  emergencyAtCreate: 'Emergency request', emergencyAtCreateHint: 'Priority is raised to critical and managers are notified after the request is created',
  emergencyReasonLabel: 'Emergency reason', createdButEmergencyFailed: 'The request was created but the emergency could not be declared: {m}. You can declare it from the work order page.',
  prefsTitle: 'Notification settings', prefsSubtitle: 'In-app notifications are always on. Choose additional external channels.',
  channelEmail: 'Email', channelSms: 'SMS',
  channelOn: 'Receive notifications by {c}',
  notAvailableEmail: 'The email provider is not configured on this system, so no email is sent until an administrator configures it.',
  notAvailableSms: 'The SMS provider is not configured on this system, so no SMS is sent until an administrator configures it.',
  availableEmail: 'Email provider ready', availableSms: 'SMS provider ready',
  savePrefs: 'Save settings', prefsSaved: 'Settings saved', inAppAlways: 'In-app notifications: always on',
  prefsHonest: 'Note: "sent" means the provider accepted the message, not that it arrived or was read.',
  deliveriesTitle: 'Notification deliveries', deliveriesSubtitle: 'Read only. Secrets and full addresses are never shown.',
  providers: 'Provider status', providerEnabled: 'Enabled', providerDisabled: 'Disabled', providerConfigured: 'Configured',
  providerNotConfigured: 'Not configured', providerProblems: 'Problems',
  filterStatus: 'Status', filterChannel: 'Channel', filterFrom: 'From date', filterTo: 'To date', filterAll: 'All',
  applyFilters: 'Apply', resetFilters: 'Clear', noDeliveries: 'No matching deliveries',
  colChannel: 'Channel', colDestination: 'Destination', colStatus: 'Status', colAttempts: 'Attempts', colProvider: 'Provider',
  colCreated: 'Created', colSent: 'Sent', colError: 'Last error', totalRows: '{n} records', invalidRange: 'The start date is after the end date',
  status_pending: 'Pending', status_failed: 'Failed (will retry)', status_sent: 'Accepted by provider', status_dead: 'Failed permanently',
  status_not_configured: 'Provider not configured', status_skipped_no_address: 'No address',
  adminOnly: 'This page is for system administrators only',
};

export const workflowDict: ModuleDict<WorkflowKey> = { ar: ar as unknown as Record<WorkflowKey, string>, en };

/** "1 h 5 min" / "ساعة" style duration used for SLA pauses. */
export function formatDuration(seconds: number | undefined | null, isAr: boolean): string {
  const s = Math.max(0, Math.floor(seconds ?? 0));
  const d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60);
  const parts: string[] = [];
  if (d) parts.push(isAr ? `${d} يوم` : `${d} d`);
  if (h) parts.push(isAr ? `${h} ساعة` : `${h} h`);
  if (m || parts.length === 0) parts.push(isAr ? `${m} دقيقة` : `${m} min`);
  return parts.join(isAr ? ' و ' : ' ');
}
