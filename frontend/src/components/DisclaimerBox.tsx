import type { CSSProperties, ReactNode } from 'react';

interface DisclaimerBoxProps {
    children: ReactNode;
    testId?: string;
    /** Spacing around the box; the look itself is shared. */
    margin?: CSSProperties['margin'];
}

/** The amber "this is a model, not a forecast" callout shared by the optimizer and the bond tent replay. */
export default function DisclaimerBox({ children, testId, margin }: DisclaimerBoxProps) {
    return (
        <div data-testid={testId} style={{
            padding: '0.75rem 1rem', margin,
            background: '#fff3e0', border: '1px solid #ffe0b2', borderRadius: '6px',
            fontSize: '0.8rem', color: '#bf360c',
        }}>
            {children}
        </div>
    );
}
