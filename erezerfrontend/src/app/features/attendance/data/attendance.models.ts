export type DayStatus = 'PRESENT' | 'LATE' | 'EARLY' | 'LATE_EARLY' | 'NO_CHECK_OUT' | 'ABSENT' | 'OFF' | 'WAITING' | 'NONE';
export type ReviewStatus = 'NONE' | 'PENDING' | 'APPROVED';
export type WeekDay = 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY';

export const WEEK_DAYS: readonly { value: WeekDay; label: string }[] = [
  { value: 'SATURDAY', label: 'Sat' },
  { value: 'SUNDAY', label: 'Sun' },
  { value: 'MONDAY', label: 'Mon' },
  { value: 'TUESDAY', label: 'Tue' },
  { value: 'WEDNESDAY', label: 'Wed' },
  { value: 'THURSDAY', label: 'Thu' },
  { value: 'FRIDAY', label: 'Fri' },
];

export const DAY_STATUS_LABELS: Record<DayStatus, string> = {
  PRESENT: 'Present',
  LATE: 'Late',
  EARLY: 'Left early',
  LATE_EARLY: 'Late, left early',
  NO_CHECK_OUT: 'No check-out',
  ABSENT: 'Absent',
  OFF: 'Day off',
  WAITING: 'Not yet',
  NONE: '–',
};

export const DAY_STATUS_CLASSES: Record<DayStatus, string> = {
  PRESENT: 'border-emerald-500/40 bg-emerald-500/10 text-emerald-300',
  LATE: 'border-amber-500/40 bg-amber-500/10 text-amber-300',
  EARLY: 'border-amber-500/40 bg-amber-500/10 text-amber-300',
  LATE_EARLY: 'border-amber-500/40 bg-amber-500/10 text-amber-300',
  NO_CHECK_OUT: 'border-sky-500/40 bg-sky-500/10 text-sky-300',
  ABSENT: 'border-rose-500/40 bg-rose-500/10 text-rose-300',
  OFF: 'border-line bg-white/5 text-slate-400',
  WAITING: 'border-line bg-white/5 text-slate-500',
  NONE: 'border-line bg-white/5 text-slate-500',
};

/** Short codes for the monthly sheet (the same as the Excel file). */
export const DAY_STATUS_CODES: Record<DayStatus, string> = {
  PRESENT: 'P',
  LATE: 'L',
  EARLY: 'E',
  LATE_EARLY: 'LE',
  NO_CHECK_OUT: 'NC',
  ABSENT: 'A',
  OFF: 'Off',
  WAITING: '',
  NONE: '',
};

/** Empty fields are left out of the API's answers, hence the optional ones. */
export interface AttendanceRecord {
  id: number;
  userId: number;
  fullName: string;
  date: string;
  shiftStart: string;
  shiftEnd: string;
  checkInAt?: string;
  checkInLocation?: string;
  checkInDistanceM?: number;
  checkInInRange: boolean;
  checkInLatitude?: number;
  checkInLongitude?: number;
  photoUrl?: string;
  checkInReason?: string;
  lateMinutes: number;
  checkOutAt?: string;
  checkOutLocation?: string;
  checkOutDistanceM?: number;
  checkOutInRange: boolean;
  checkOutLatitude?: number;
  checkOutLongitude?: number;
  checkOutReason?: string;
  earlyMinutes: number;
  reviewStatus: ReviewStatus;
  lateFee: number;
  reviewedBy?: string;
  reviewedAt?: string;
  reviewNote?: string;
  editNote?: string;
  /** Only once the day has both a check-in and a check-out. */
  workedMinutes?: number;
}

export interface Shift {
  userId: number;
  fullName: string;
  checkIn?: string;
  checkOut?: string;
  graceMinutes: number;
  weeklyOff: WeekDay[];
  /** Holds the attendance permission. */
  canCheckIn: boolean;
}

export interface MyToday {
  date: string;
  shift?: Shift;
  weeklyOff: boolean;
  record?: AttendanceRecord;
  /** Today's hours, which special hours can change. */
  todayCheckIn?: string;
  todayCheckOut?: string;
}

export interface SpecialHours {
  id: number;
  userId?: number;
  personName?: string;
  weekDay?: WeekDay;
  dateFrom?: string;
  dateTo?: string;
  dayOff: boolean;
  checkIn?: string;
  checkOut?: string;
  note?: string;
}

export interface SpecialHoursRequest {
  userId: number | null;
  weekDay: WeekDay | null;
  dateFrom: string | null;
  dateTo: string | null;
  dayOff: boolean;
  checkIn: string | null;
  checkOut: string | null;
  note: string | null;
}

export interface WorkLocation {
  id: number;
  name: string;
  latitude?: number;
  longitude?: number;
  radiusM: number;
  active: boolean;
  used: boolean;
}

export interface DayRow {
  userId: number;
  fullName: string;
  date: string;
  shiftStart: string;
  shiftEnd: string;
  status: DayStatus;
  record?: AttendanceRecord;
}

export interface DayCell {
  date: string;
  status: DayStatus;
  lateMinutes: number;
  earlyMinutes: number;
  lateFee: number;
  checkInAt?: string;
  checkOutAt?: string;
}

export interface MonthRow {
  userId: number;
  fullName: string;
  days: DayCell[];
  present: number;
  late: number;
  early: number;
  absent: number;
  off: number;
  lateFees: number;
  workedMinutes: number;
}

export interface MonthSheet {
  month: string;
  dates: string[];
  rows: MonthRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface EmployeeOption {
  userId: number;
  fullName: string;
}

export interface AttendanceSummary {
  workedMinutes: number;
  lateFees: number;
}

export interface Position {
  latitude: number;
  longitude: number;
}

/** "09:30:00" → "9:30 AM". */
export function time12(time: string | undefined): string {
  if (!time) return '–';
  const [hour, minute] = time.split(':').map(Number);
  return `${hour % 12 || 12}:${String(minute).padStart(2, '0')} ${hour < 12 ? 'AM' : 'PM'}`;
}

/** 450 → "7h 30m". */
export function hoursLabel(minutes: number): string {
  return `${Math.floor(minutes / 60)}h ${minutes % 60}m`;
}

/** Where a position is on the map. */
export function mapLink(latitude: number | undefined, longitude: number | undefined): string | null {
  return latitude === undefined || longitude === undefined ? null : `https://www.google.com/maps?q=${latitude},${longitude}`;
}

/** The phone's position, or null when it is refused or can't be found. */
export function currentPosition(): Promise<Position | null> {
  return new Promise((resolve) => {
    if (!navigator.geolocation) {
      resolve(null);
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (p) => resolve({ latitude: p.coords.latitude, longitude: p.coords.longitude }),
      () => resolve(null),
      { enableHighAccuracy: true, timeout: 15_000, maximumAge: 30_000 },
    );
  });
}
