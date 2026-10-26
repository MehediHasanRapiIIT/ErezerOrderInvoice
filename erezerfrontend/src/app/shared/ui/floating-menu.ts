/**
 * Row "⋮" menus inside scrolling tables: a menu placed with `position: absolute` is cut off by the table's scroll
 * box (with one or two rows there is no room below). These helpers place it with `position: fixed` next to the
 * button instead, opening upwards when there isn't room below, and close it when anything scrolls or the window
 * resizes (it would otherwise drift away from its row).
 */
export interface MenuPosition {
  top: number | null;
  bottom: number | null;
  right: number;
}

export const CLOSED_MENU: MenuPosition = { top: null, bottom: null, right: 0 };

/**
 * Where to show the menu for the clicked button, and arranges for {@link onClose} to run on the next scroll or
 * resize.
 *
 * @param menuHeight roughly how tall the menu is, to decide between below and above
 */
export function placeMenu(event: Event, onClose: () => void, menuHeight = 170): MenuPosition {
  const button = (event.currentTarget ?? event.target) as HTMLElement;
  const rect = button.getBoundingClientRect();
  const close = () => {
    window.removeEventListener('scroll', close, true);
    window.removeEventListener('resize', close);
    onClose();
  };
  window.addEventListener('scroll', close, { capture: true, once: true });
  window.addEventListener('resize', close, { once: true });
  const right = Math.max(window.innerWidth - rect.right, 8);
  const roomBelow = window.innerHeight - rect.bottom;
  return roomBelow >= menuHeight + 8
    ? { top: rect.bottom + 4, bottom: null, right }
    : { top: null, bottom: window.innerHeight - rect.top + 4, right };
}
