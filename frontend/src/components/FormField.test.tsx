import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect } from 'vitest';
import FormField from './FormField';
import CurrencyInput from './CurrencyInput';

describe('FormField', () => {
    it('associates the label with a single input child', () => {
        render(<FormField label="Name"><input /></FormField>);

        expect(screen.getByLabelText('Name')).toBeInstanceOf(HTMLInputElement);
    });

    it('works for select and textarea children', () => {
        render(
            <>
                <FormField label="Type"><select><option>a</option></select></FormField>
                <FormField label="Notes"><textarea /></FormField>
            </>,
        );

        expect(screen.getByLabelText('Type')).toBeInstanceOf(HTMLSelectElement);
        expect(screen.getByLabelText('Notes')).toBeInstanceOf(HTMLTextAreaElement);
    });

    it('works for a wrapper component that forwards its id to an input', () => {
        render(<FormField label="Amount"><CurrencyInput value="" onChange={() => {}} /></FormField>);

        expect(screen.getByLabelText('Amount')).toBeInstanceOf(HTMLInputElement);
    });

    it('focuses the field when the label is clicked', async () => {
        render(<FormField label="Name"><input /></FormField>);

        await userEvent.click(screen.getByText('Name'));

        expect(screen.getByLabelText('Name')).toHaveFocus();
    });

    it('keeps an id the child already has and points the label at it', () => {
        render(<FormField label="Email"><input id="email-field" /></FormField>);

        expect(screen.getByLabelText('Email')).toHaveAttribute('id', 'email-field');
    });

    it('gives each field in a form a distinct id', () => {
        render(
            <>
                <FormField label="First"><input /></FormField>
                <FormField label="Second"><input /></FormField>
            </>,
        );

        expect(screen.getByLabelText('First').id).not.toBe(screen.getByLabelText('Second').id);
    });

    it('renders multiple children without cloning and still shows the label', () => {
        render(<FormField label="Range"><input aria-label="from" /><input aria-label="to" /></FormField>);

        expect(screen.getByText('Range')).toBeInTheDocument();
        expect(screen.getByLabelText('from')).toBeInTheDocument();
    });

    it('renders help text when provided', () => {
        render(<FormField label="Name" helpText="Your full name"><input /></FormField>);

        expect(screen.getByText('Your full name')).toBeInTheDocument();
    });
});
