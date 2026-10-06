import { useEffect, useRef } from 'react';

const FOCUSABLE = 'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])';

/**
 * Behaviour every modal dialog needs besides its markup: focus moves into the dialog when it opens
 * and returns to whatever had it when it closes, and Escape closes it. Attach the returned ref to the
 * element carrying `role="dialog"` (give it `tabIndex={-1}` so it can take focus when it holds nothing
 * focusable). The hook is for conditionally rendered dialogs: it runs for as long as the host component
 * that renders the dialog is mounted with the dialog present.
 */
export function useModalDialog<T extends HTMLElement>(onClose: () => void) {
    const ref = useRef<T>(null);
    const onCloseRef = useRef(onClose);

    useEffect(() => {
        onCloseRef.current = onClose;
    }, [onClose]);

    useEffect(() => {
        const previouslyFocused = document.activeElement as HTMLElement | null;
        const dialog = ref.current;
        (dialog?.querySelector<HTMLElement>(FOCUSABLE) ?? dialog)?.focus();

        function handleKeyDown(e: KeyboardEvent) {
            if (e.key === 'Escape') onCloseRef.current();
        }
        document.addEventListener('keydown', handleKeyDown);
        return () => {
            document.removeEventListener('keydown', handleKeyDown);
            previouslyFocused?.focus?.();
        };
    }, []);

    return ref;
}
