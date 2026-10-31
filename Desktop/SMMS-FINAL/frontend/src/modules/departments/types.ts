import type { CenterRef } from '@/modules/assets/types';

export interface Department {
  id: string;
  name: string;
  nameAr: string;
  code: string;
  active: boolean;
  center?: CenterRef;
}

export interface DepartmentFormValues {
  centerId: string;
  name: string;
  nameAr: string;
  code: string;
  active: boolean;
}

export interface Member {
  id: string;
  fullName: string;
  fullNameAr?: string | null;
  role: string;
}

/** A user as returned by GET /admin/users (only the fields this module reads). */
export interface UserLite {
  id: string;
  fullName: string;
  fullNameAr?: string | null;
  username: string;
  role: string;
  departmentId?: string | null;
  departmentName?: string | null;
  center?: CenterRef | null;
}
