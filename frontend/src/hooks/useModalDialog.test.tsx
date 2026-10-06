import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { useModalDialog } from './useModalDialog';

function Harness({ onClose, withInput = true }: { onClose: () => void; withInput?: boolean }) {
    const ref = useModalDialog<HTMLDivElement>(onClose);
    return (
        <div>
            <button>outside</button>
            <div ref={ref} role="dialog" aria-modal="true" aria-label="Test dialog" tabIndex={-1}>
                {withInput && <input aria-label="first field" />}
                <button>inside</button>
            </div>
        </div>
    );
}

describe('useModalDialog', () => {
    it('moves focus to the first focusable element when it opens', () => {
        render(<Harness onClose={vi.fn()} />);

        expect(screen.getByLabelText('first field')).toHaveFocus();
    });

    it('focuses the dialog itself when it has nothing focusable', () => {
        function Empty() {
            const ref = useModalDialog<HTMLDivElement>(vi.fn());
            return <div ref={ref} role="dialog" aria-label="Empty" tabIndex={-1}>text only</div>;
        }
        render(<Empty />);

        expect(screen.getByRole('dialog')).toHaveFocus();
    });

    it('calls onClose when Escape is pressed', () => {
        const onClose = vi.fn();
        render(<Harness onClose={onClose} />);

        fireEvent.keyDown(document, { key: 'Escape' });

        expect(onClose).toHaveBeenCalledTimes(1);
    });

    it('ignores other keys', () => {
        const onClose = vi.fn();
        render(<Harness onClose={onClose} />);

        fireEvent.keyDown(document, { key: 'Enter' });

        expect(onClose).not.toHaveBeenCalled();
    });

    it('returns focus to the previously focused element when it closes', () => {
        const trigger = document.createElement('button');
        document.body.appendChild(trigger);
        trigger.focus();
        const { unmount } = render(<Harness onClose={vi.fn()} />);

        unmount();

        expect(trigger).toHaveFocus();
        trigger.remove();
    });

    it('stops listening after unmount', () => {
        const onClose = vi.fn();
        const { unmount } = render(<Harness onClose={onClose} />);
        unmount();

        fireEvent.keyDown(document, { key: 'Escape' });

        expect(onClose).not.toHaveBeenCalled();
    });
});
