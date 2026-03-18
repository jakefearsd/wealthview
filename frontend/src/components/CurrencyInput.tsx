import { useRef, useLayoutEffect } from 'react';
import { formatCurrencyInput, parseCurrencyInput } from '../utils/format';
import type { InputHTMLAttributes } from 'react';

interface CurrencyInputProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'onChange' | 'value' | 'type' | 'inputMode'> {
    value: string | number;
    onChange: (rawValue: string) => void;
}

function commasBefore(str: string, rawOffset: number): number {
    let raw = 0, commas = 0;
    for (let i = 0; i < str.length; i++) {
        if (raw >= rawOffset) break;
        str[i] === ',' ? commas++ : raw++;
    }
    return commas;
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

    function handleChange(e: React.ChangeEvent<HTMLInputElement>) {
        const selStart = e.target.selectionStart ?? e.target.value.length;
        const commaCount = (e.target.value.slice(0, selStart).match(/,/g) ?? []).length;
        const rawOffset = selStart - commaCount;

        const raw = parseCurrencyInput(e.target.value);
        const newFormatted = formatCurrencyInput(raw);
        cursorRef.current = rawOffset + commasBefore(newFormatted, rawOffset);

        onChange(raw);
    }

    return (
        <input
            ref={inputRef}
            type="text"
            inputMode="decimal"
            value={formatCurrencyInput(value)}
            onChange={handleChange}
            {...rest}
        />
    );
}
