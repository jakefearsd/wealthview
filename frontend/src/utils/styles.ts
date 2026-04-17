import type { CSSProperties } from 'react';

export const cardStyle: CSSProperties = {
    background: '#fff',
    padding: '1.5rem',
    borderRadius: '8px',
    boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
};

export const inputStyle: CSSProperties = {
    padding: '0.5rem',
    border: '1px solid #ccc',
    borderRadius: '4px',
    width: '100%',
};

/** Sibling of inputStyle without the 100% width — for flex rows and inline inputs. */
export const inputFieldStyle: CSSProperties = {
    padding: '0.5rem',
    border: '1px solid #ccc',
    borderRadius: '4px',
};

export const selectStyle: CSSProperties = {
    padding: '0.5rem',
    border: '1px solid #ccc',
    borderRadius: '4px',
    background: '#fff',
    cursor: 'pointer',
};

export const labelStyle: CSSProperties = {
    display: 'block',
    marginBottom: '0.25rem',
    fontWeight: 600,
    fontSize: '0.85rem',
};

export const tableStyle: CSSProperties = {
    width: '100%',
    borderCollapse: 'collapse',
    fontSize: '0.85rem',
};

export const thStyle: CSSProperties = {
    textAlign: 'left',
    padding: '0.5rem',
    borderBottom: '2px solid #e0e0e0',
    fontSize: '0.8rem',
    fontWeight: 600,
    color: '#555',
};

export const tdStyle: CSSProperties = {
    padding: '0.4rem 0.5rem',
    borderBottom: '1px solid #eee',
};

export const trHoverStyle: CSSProperties = {
    borderBottom: '1px solid #eee',
};

export const tooltipStyle: CSSProperties = {
    background: '#fff',
    border: '1px solid #ccc',
    padding: '0.75rem',
    borderRadius: 4,
    fontSize: '0.85rem',
};
