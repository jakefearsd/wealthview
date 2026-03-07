interface HelpTextProps {
    children: React.ReactNode;
}

export default function HelpText({ children }: HelpTextProps) {
    return (
        <span style={{ fontSize: '0.75rem', color: '#888', display: 'block', lineHeight: 1.3, marginTop: '0.15rem' }}>
            {children}
        </span>
    );
}
