import type { ReactNode } from 'react';
import HelpText from './HelpText';
import { labelStyle } from '../utils/styles';

interface FormFieldProps {
    label: string;
    helpText?: string;
    children: ReactNode;
}

export default function FormField({ label, helpText, children }: FormFieldProps) {
    return (
        <div>
            <label style={labelStyle}>{label}</label>
            {children}
            {helpText && <HelpText>{helpText}</HelpText>}
        </div>
    );
}
