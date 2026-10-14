import { Pipe, PipeTransform } from '@angular/core';

const FORMAT = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** Formats an amount as Bangladeshi Taka: 1810 → "৳ 1,810.00". */
@Pipe({ name: 'taka' })
export class TakaPipe implements PipeTransform {
  transform(value: number | null | undefined): string {
    return value == null || Number.isNaN(value) ? '—' : `৳ ${FORMAT.format(value)}`;
  }
}
