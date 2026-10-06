import type { ReactNode } from 'react';

const hiddenStyle = {
    position: 'absolute',
    width: '1px',
    height: '1px',
    margin: '-1px',
    padding: 0,
    overflow: 'hidden',
    clip: 'rect(0 0 0 0)',
    whiteSpace: 'nowrap',
    border: 0,
} as const;

/** Text for assistive technology only, such as the header of an icon or action column. */
export default function VisuallyHidden({ children }: { children: ReactNode }) {
    return <span style={hiddenStyle}>{children}</span>;
}
