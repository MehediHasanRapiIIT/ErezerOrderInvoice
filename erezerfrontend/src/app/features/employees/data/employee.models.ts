export type AccountType = 'BANK' | 'BKASH' | 'NAGAD' | 'ROCKET' | 'OTHER';

export const ACCOUNT_TYPES: readonly AccountType[] = ['BANK', 'BKASH', 'NAGAD', 'ROCKET', 'OTHER'];

export const ACCOUNT_TYPE_LABELS: Record<AccountType, string> = {
  BANK: 'Bank account',
  BKASH: 'bKash',
  NAGAD: 'Nagad',
  ROCKET: 'Rocket',
  OTHER: 'Other',
};

export interface SalaryAccount {
  type: AccountType;
  provider?: string | null;
  accountName?: string | null;
  accountNumber: string;
  /** Bank accounts only. */
  branch?: string | null;
  routingNumber?: string | null;
}

export interface EmployeeFile {
  fileId: number;
  url: string;
}

/** A row of the list. */
export interface EmployeeSummary {
  id: number;
  employeeCode: string;
  fullName: string;
  phone: string;
  nidNumber: string;
  presentAddress: string;
  photoUrl: string;
}

/** Empty fields are left out of the API's answers, hence the optional ones. */
export interface Employee extends EmployeeSummary {
  fatherName?: string;
  fatherPhone?: string;
  motherName?: string;
  motherPhone?: string;
  permanentAddress?: string;
  photo: EmployeeFile;
  nidImage: EmployeeFile;
  idCardImage?: EmployeeFile;
  salaryAccounts: SalaryAccount[];
  createdAt: string;
  version: number;
}

export interface EmployeeRequest {
  employeeCode: string;
  fullName: string;
  phone: string;
  fatherName: string | null;
  fatherPhone: string | null;
  motherName: string | null;
  motherPhone: string | null;
  presentAddress: string;
  permanentAddress: string | null;
  nidNumber: string;
  photoFileId: number;
  nidImageFileId: number;
  idCardImageFileId: number | null;
  salaryAccounts: SalaryAccount[];
  version?: number;
}
