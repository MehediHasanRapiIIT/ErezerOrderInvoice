import { ReviewStatus } from './attendance.models';

export type RemoteStatus =
  | 'WORKING'
  | 'PRESENT'
  | 'LATE'
  | 'EARLY'
  | 'LATE_EARLY'
  | 'NOT_TURNED_OFF'
  | 'ABSENT'
  | 'OFF'
  | 'WAITING'
  | 'NONE';

export const REMOTE_STATUS_LABELS: Record<RemoteStatus, string> = {
  WORKING: 'On now',
  PRESENT: 'Worked',
  LATE: 'Late',
  EARLY: 'Finished early',
  LATE_EARLY: 'Late, finished early',
  NOT_TURNED_OFF: 'Forgot Reply Off',
  ABSENT: 'Absent',
  OFF: 'Day off',
  WAITING: 'Not yet',
  NONE: '–',
};

export const REMOTE_STATUS_CLASSES: Record<RemoteStatus, string> = {
  WORKING: 'border-emerald-500/60 bg-emerald-500/20 text-emerald-200',
  PRESENT: 'border-emerald-500/40 bg-emerald-500/10 text-emerald-300',
  LATE: 'border-amber-500/40 bg-amber-500/10 text-amber-300',
  EARLY: 'border-amber-500/40 bg-amber-500/10 text-amber-300',
  LATE_EARLY: 'border-amber-500/40 bg-amber-500/10 text-amber-300',
  NOT_TURNED_OFF: 'border-sky-500/40 bg-sky-500/10 text-sky-300',
  ABSENT: 'border-rose-500/40 bg-rose-500/10 text-rose-300',
  OFF: 'border-line bg-white/5 text-slate-400',
  WAITING: 'border-line bg-white/5 text-slate-500',
  NONE: 'border-line bg-white/5 text-slate-500',
};

/** Short codes for the calendar. */
export const REMOTE_STATUS_CODES: Record<RemoteStatus, string> = {
  WORKING: 'On',
  PRESENT: 'W',
  LATE: 'L',
  EARLY: 'E',
  LATE_EARLY: 'LE',
  NOT_TURNED_OFF: 'NO',
  ABSENT: 'A',
  OFF: 'Off',
  WAITING: '',
  NONE: '',
};

export interface RemoteSession {
  id: number;
  onAt: string;
  offAt?: string;
  minutes: number;
  /** Closed by the system because Reply Off was never pressed. */
  autoClosed: boolean;
  imageUrl?: string;
  note?: string;
  offNote?: string;
  editNote?: string;
}

/** One person's day. Days with nothing recorded have no id. */
export interface RemoteDay {
  id?: number;
  userId: number;
  fullName: string;
  date: string;
  shiftStart?: string;
  shiftEnd?: string;
  status: RemoteStatus;
  lateMinutes: number;
  earlyMinutes: number;
  lateReason?: string;
  earlyReason?: string;
  notTurnedOff: boolean;
  reviewStatus: ReviewStatus;
  lateFee: number;
  reviewedBy?: string;
  reviewNote?: string;
  workedMinutes: number;
  on: boolean;
  sessions: RemoteSession[];
}

export interface RemoteMe {
  date: string;
  remote: boolean;
  start?: string;
  end?: string;
  dayOff: boolean;
  on: boolean;
  onSince?: string;
  workedMinutes: number;
  day?: RemoteDay;
}

export interface RemoteSummary {
  workedMinutes: number;
  daysWorked: number;
  lateDays: number;
  earlyDays: number;
  notTurnedOff: number;
  lateFees: number;
}

export interface RemoteMonthCell {
  date: string;
  status: RemoteStatus;
  workedMinutes: number;
  lateFee: number;
}

export interface RemoteMonth {
  summary: RemoteSummary;
  days: RemoteMonthCell[];
}

export interface RemotePerson {
  userId: number;
  fullName: string;
  on: boolean;
  onSince?: string;
  workedMinutes: number;
  daysWorked: number;
  lateDays: number;
  earlyDays: number;
  notTurnedOff: number;
  lateFees: number;
}

export interface SessionRequest {
  userId: number;
  date: string;
  on: string;
  off: string | null;
  note: string;
}
