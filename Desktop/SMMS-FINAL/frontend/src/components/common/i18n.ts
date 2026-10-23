import type { ModuleDict } from '@/lib/module-i18n';

const ar = {
  loading: 'جاري التحميل...',
  emptyTitle: 'لا توجد بيانات',
  errorTitle: 'حدث خطأ',
  errorBody: 'تعذر تحميل البيانات. حاول مرة أخرى.',
  retry: 'إعادة المحاولة',
  deniedTitle: 'غير مصرح',
  deniedBody: 'ليس لديك صلاحية للوصول إلى هذه الصفحة.',
  required: 'مطلوب',
  previous: 'السابق',
  next: 'التالي',
  pageOf: 'صفحة {page} من {total}',
  pagination: 'التنقل بين الصفحات',
  confirm: 'تأكيد',
  cancel: 'إلغاء',
  working: 'جاري التنفيذ...',
} as const;

const en: Partial<Record<keyof typeof ar, string>> = {
  loading: 'Loading...',
  emptyTitle: 'No data',
  errorTitle: 'Something went wrong',
  errorBody: 'Could not load the data. Please try again.',
  retry: 'Retry',
  deniedTitle: 'Access denied',
  deniedBody: 'You do not have permission to view this page.',
  required: 'required',
  previous: 'Previous',
  next: 'Next',
  pageOf: 'Page {page} of {total}',
  pagination: 'Pagination',
  confirm: 'Confirm',
  cancel: 'Cancel',
  working: 'Working...',
};

export type CommonKey = keyof typeof ar;
export const commonDict: ModuleDict<CommonKey> = { ar, en };
