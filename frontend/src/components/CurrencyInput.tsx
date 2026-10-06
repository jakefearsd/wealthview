import { useRef, useLayoutEffect } from 'react';
import { formatCurrencyInput } from '../utils/format';
import type { InputHTMLAttributes } from 'react';

interface CurrencyInputProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'onChange' | 'value' | 'type' | 'inputMode'> {
    value: string | number;
    onChange: (rawValue: string) => void;
}

/** Most decimal places kept: prices and cost basis are numeric(19,4). */
const MAX_DECIMALS = 4;

/**
 * Reduce whatever was typed or pasted to a raw numeric string: digits, at most one '.', and a
 * leading '-'. Letters, currency symbols and grouping commas are dropped, so state never holds
 * text that parseFloat would turn into NaN or silently truncate ("12k" -> 12).
 */
function toRawNumber(display: string): string {
    const negative = display.trimStart().startsWith('-');
    const cleaned = display.replace(/[^0-9.]/g, '');
    const dot = cleaned.indexOf('.');
    const numeric = dot === -1
        ? cleaned
        : `${cleaned.slice(0, dot + 1)}${cleaned.slice(dot + 1).replace(/\./g, '').slice(0, MAX_DECIMALS)}`;
    return negative ? `-${numeric}` : numeric;
}

export default function CurrencyInput({ value, onChange, ...rest }: CurrencyInputProps) {
    const inputRef = useRef<HTMLInputElement>(null);
    const cursorRef = useRef<number | null>(null);

    useLayoutEffect(() => {
        if (inputRef.current && cursorRef.current !== null) {
            inputRef.current.setSelectionRange(cursorRef.current, cursorRef.current);
            cursorRef.current = null;
        }
    });

    function handleKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
        const el = e.currentTarget;
        const pos = el.selectionStart ?? 0;
        const val = el.value;

        if (e.key === 'Backspace' && pos > 0 && val[pos - 1] === ',') {
            // Backspace at a comma: skip the comma and delete the digit before it
            e.preventDefault();
            const before = val.slice(0, pos - 2); // skip comma AND the digit before it
            const after = val.slice(pos);
            const newVal = before + after;
            const raw = toRawNumber(newVal);
            const newFormatted = formatCurrencyInput(raw);
            const digitsBefore = before.replace(/[^0-9.]/g, '').length;
            let digits = 0;
            let newCursor = 0;
            for (let i = 0; i < newFormatted.length; i++) {
                if (digits >= digitsBefore) break;
                if (newFormatted[i] !== ',') digits++;
                newCursor = i + 1;
            }
            cursorRef.current = newCursor;
            onChange(raw);
        } else if (e.key === 'Delete' && pos < val.length && val[pos] === ',') {
            // Delete at a comma: skip the comma and delete the digit after it
            e.preventDefault();
            const before = val.slice(0, pos);
            const after = val.slice(pos + 2); // skip comma AND the digit after it
            const newVal = before + after;
            const raw = toRawNumber(newVal);
            const newFormatted = formatCurrencyInput(raw);
            const digitsBefore = before.replace(/[^0-9.]/g, '').length;
            let digits = 0;
            let newCursor = 0;
            for (let i = 0; i < newFormatted.length; i++) {
                if (digits >= digitsBefore) break;
                if (newFormatted[i] !== ',') digits++;
                newCursor = i + 1;
            }
            cursorRef.current = newCursor;
            onChange(raw);
        }
    }

    function handleChange(e: React.ChangeEvent<HTMLInputElement>) {
        const el = e.target;
        const cursorPos = el.selectionStart ?? el.value.length;

        // Count digits (and dots) before cursor in the current displayed value
        const digitsBefore = el.value.slice(0, cursorPos).replace(/[^0-9.]/g, '').length;

        const raw = toRawNumber(el.value);
        const newFormatted = formatCurrencyInput(raw);

        // Find cursor position in new formatted string with same digit count before it
        let digits = 0;
        let newCursor = 0;
        for (let i = 0; i < newFormatted.length; i++) {
            if (digits >= digitsBefore) break;
            if (newFormatted[i] !== ',') digits++;
            newCursor = i + 1;
        }

        cursorRef.current = newCursor;
        onChange(raw);
    }

    return (
        <input
            ref={inputRef}
            type="text"
            inputMode="decimal"
            value={formatCurrencyInput(value)}
            onKeyDown={handleKeyDown}
            onChange={handleChange}
            {...rest}
        />
    );
}
