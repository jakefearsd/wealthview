import { render, screen, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import { useState } from 'react';
import CurrencyInput from './CurrencyInput';

function Harness({ initial = '', onChange }: { initial?: string; onChange?: (raw: string) => void }) {
    const [value, setValue] = useState<string>(initial);
    return (
        <CurrencyInput
            data-testid="ci"
            value={value}
            onChange={(raw) => {
                setValue(raw);
                onChange?.(raw);
            }}
        />
    );
}

describe('CurrencyInput', () => {
    it('formats the bound value with grouping commas', () => {
        render(<Harness initial="1234567" />);

        const input = screen.getByTestId('ci') as HTMLInputElement;
        expect(input.value).toBe('1,234,567');
    });

    it('passes through arbitrary input attributes (placeholder, name)', () => {
        function NameWrap() {
            const [v, setV] = useState('');
            return (
                <CurrencyInput
                    data-testid="ci"
                    value={v}
                    onChange={setV}
                    placeholder="amount"
                    name="amount"
                />
            );
        }
        render(<NameWrap />);

        const input = screen.getByTestId('ci') as HTMLInputElement;
        expect(input.placeholder).toBe('amount');
        expect(input.name).toBe('amount');
        expect(input.type).toBe('text');
        expect(input.inputMode).toBe('decimal');
    });

    it('calls onChange with the raw (un-comma-fied) value as the user types', async () => {
        const user = userEvent.setup();
        const onChange = vi.fn();
        render(<Harness onChange={onChange} />);

        const input = screen.getByTestId('ci') as HTMLInputElement;
        await user.type(input, '1234');

        // The displayed value should reflect grouping by the time the user is done.
        expect(input.value).toBe('1,234');
        // Last raw value pushed to onChange should be the un-grouped form.
        expect(onChange).toHaveBeenLastCalledWith('1234');
    });

    it('strips commas from pasted input and stores the raw numeric string', async () => {
        const user = userEvent.setup();
        const onChange = vi.fn();
        render(<Harness onChange={onChange} />);

        const input = screen.getByTestId('ci') as HTMLInputElement;
        await user.click(input);
        await user.paste('1,234,567');

        expect(input.value).toBe('1,234,567');
        expect(onChange).toHaveBeenLastCalledWith('1234567');
    });

    it('Backspace at a comma deletes the digit before the comma rather than the comma itself', () => {
        const onChange = vi.fn();
        render(<Harness initial="1234" onChange={onChange} />);

        const input = screen.getByTestId('ci') as HTMLInputElement;
        // Initial display is "1,234" — place caret right after the comma (position 2)
        input.focus();
        input.setSelectionRange(2, 2);
        fireEvent.keyDown(input, { key: 'Backspace' });

        // Leading "1" should have been removed via the comma special-case, leaving "234" raw
        expect(onChange).toHaveBeenLastCalledWith('234');
    });

    it('Delete at a comma removes the digit just after the comma', () => {
        const onChange = vi.fn();
        render(<Harness initial="1234" onChange={onChange} />);

        const input = screen.getByTestId('ci') as HTMLInputElement;
        // "1,234" — place caret right before the comma (position 1)
        input.focus();
        input.setSelectionRange(1, 1);
        fireEvent.keyDown(input, { key: 'Delete' });

        // The "2" right after the comma is removed -> "134"
        expect(onChange).toHaveBeenLastCalledWith('134');
    });

    it('non-comma keys do not trigger the special-case handler', () => {
        const onChange = vi.fn();
        render(<Harness initial="1234" onChange={onChange} />);

        const input = screen.getByTestId('ci') as HTMLInputElement;
        input.focus();
        input.setSelectionRange(5, 5); // end of "1,234"
        fireEvent.keyDown(input, { key: 'a' });

        // No keyDown handling for non-special keys -> no onChange.
        expect(onChange).not.toHaveBeenCalled();
    });

    it('drops letters so typed text never reaches state', async () => {
        const user = userEvent.setup();
        const onChange = vi.fn();
        render(<Harness onChange={onChange} />);

        const input = screen.getByTestId('ci') as HTMLInputElement;
        await user.type(input, 'abc');

        expect(input.value).toBe('');
        expect(onChange).toHaveBeenLastCalledWith('');
    });

    it('keeps only the digits of mixed input like "12k"', async () => {
        const user = userEvent.setup();
        const onChange = vi.fn();
        render(<Harness onChange={onChange} />);

        await user.type(screen.getByTestId('ci'), '12k');

        expect(onChange).toHaveBeenLastCalledWith('12');
    });

    it('strips currency symbols and grouping from a pasted "$1,234.56"', async () => {
        const user = userEvent.setup();
        const onChange = vi.fn();
        render(<Harness onChange={onChange} />);

        const input = screen.getByTestId('ci') as HTMLInputElement;
        await user.click(input);
        await user.paste('$1,234.56');

        expect(onChange).toHaveBeenLastCalledWith('1234.56');
        expect(input.value).toBe('1,234.56');
    });

    it('allows only one decimal point and a leading minus', async () => {
        const user = userEvent.setup();
        const onChange = vi.fn();
        render(<Harness onChange={onChange} />);

        const input = screen.getByTestId('ci') as HTMLInputElement;
        await user.click(input);
        await user.paste('-1.2.3-4');

        expect(onChange).toHaveBeenLastCalledWith('-1.234');
    });

    it('shows and stores up to four decimals so the display matches what is saved', async () => {
        const user = userEvent.setup();
        const onChange = vi.fn();
        render(<Harness onChange={onChange} />);

        const input = screen.getByTestId('ci') as HTMLInputElement;
        await user.type(input, '185.50256');

        expect(input.value).toBe('185.5025');
        expect(onChange).toHaveBeenLastCalledWith('185.5025');
    });

    it('displays a four-decimal bound value without truncating or padding it', () => {
        render(<Harness initial="185.5025" />);

        expect((screen.getByTestId('ci') as HTMLInputElement).value).toBe('185.5025');
    });
});
