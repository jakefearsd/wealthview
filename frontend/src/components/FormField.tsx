import { Children, cloneElement, Fragment, isValidElement, useId } from 'react';
import type { ReactElement, ReactNode } from 'react';
import HelpText from './HelpText';
import { labelStyle } from '../utils/styles';

interface FormFieldProps {
    label: string;
    helpText?: string;
    children: ReactNode;
}

export default function FormField({ label, helpText, children }: FormFieldProps) {
    const generatedId = useId();

    // Associate the label with a single element child: reuse its own id, or give it a generated
    // one. Without this the input has no accessible name and clicking the label does nothing.
    const only = Children.count(children) === 1 ? Children.toArray(children)[0] : null;
    const field = isValidElement<{ id?: string }>(only) && only.type !== Fragment ? only : null;
    const fieldId = field ? (field.props.id ?? generatedId) : undefined;

    return (
        <div>
            <label htmlFor={fieldId} style={labelStyle}>{label}</label>
            {field
                ? cloneElement(field as ReactElement<{ id?: string }>, { id: fieldId })
                : children}
            {helpText && <HelpText>{helpText}</HelpText>}
        </div>
    );
}
