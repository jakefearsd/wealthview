/**
 * Props for a wrapper that scrolls (overflow auto). A scrollable container must be keyboard
 * focusable and named, otherwise keyboard users cannot scroll it (axe scrollable-region-focusable).
 */
export function scrollRegionProps(label: string) {
    return { role: 'region', tabIndex: 0, 'aria-label': label } as const;
}
