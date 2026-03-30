import type { ButtonHTMLAttributes } from 'react';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
    variant?: 'primary' | 'secondary' | 'danger';
    size?: 'sm' | 'md';
}

const baseStyle: React.CSSProperties = {
    borderRadius: '4px',
    cursor: 'pointer',
    fontWeight: 600,
};

const variantStyles: Record<string, React.CSSProperties> = {
    primary: { background: '#1976d2', color: '#fff', border: 'none' },
    secondary: { background: 'transparent', color: '#1976d2', border: '1px solid #1976d2' },
    danger: { background: '#d32f2f', color: '#fff', border: 'none' },
};

const sizeStyles: Record<string, React.CSSProperties> = {
    sm: { padding: '0.3rem 0.6rem', fontSize: '0.8rem' },
    md: { padding: '0.5rem 1rem', fontSize: '0.875rem' },
};

const disabledStyle: React.CSSProperties = {
    opacity: 0.5,
    cursor: 'not-allowed',
};

export default function Button({
    variant = 'primary',
    size = 'md',
    disabled,
    style,
    ...rest
}: ButtonProps) {
    const combined: React.CSSProperties = {
        ...baseStyle,
        ...variantStyles[variant],
        ...sizeStyles[size],
        ...(disabled ? disabledStyle : {}),
        ...style,
    };

    return <button disabled={disabled} style={combined} {...rest} />;
}
