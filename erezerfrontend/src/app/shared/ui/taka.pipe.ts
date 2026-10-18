import { Pipe, PipeTransform } from '@angular/core';

const FORMAT = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** Formats an amount as Bangladeshi Taka: 1810 → "৳ 1,810.00", -4090.5 → "−৳ 4,090.50". */
@Pipe({ name: 'taka' })
export class TakaPipe implements PipeTransform {
  transform(value: number | null | undefined): string {
    if (value == null || Number.isNaN(value)) {
      return '—';
    }
    return value < 0 ? `−৳ ${FORMAT.format(-value)}` : `৳ ${FORMAT.format(value)}`;
  }
}
