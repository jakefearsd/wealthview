import { useState } from 'react';
import CurrencyInput from './CurrencyInput';
import { cardStyle } from '../utils/styles';

interface CategoryOption {
    value: string;
    label: string;
}

interface Props {
    title: string;
    categories: CategoryOption[];
    /** Resolves true when the entry was saved; the form keeps its values on false. */
    onSubmit: (data: { date: string; amount: number; category: string; description?: string; frequency?: string }) => Promise<boolean>;
    buttonColor: string;
}

export default function PropertyTransactionForm({ title, categories, onSubmit, buttonColor }: Props) {
    const [date, setDate] = useState('');
    const [amount, setAmount] = useState('');
    const [category, setCategory] = useState(categories[0]?.value ?? '');
    const [description, setDescription] = useState('');
    const [frequency, setFrequency] = useState('monthly');
    const [saving, setSaving] = useState(false);
    const [validationError, setValidationError] = useState<string | null>(null);

    async function handleSubmit() {
        const parsedAmount = parseFloat(amount);
        if (!date) {
            setValidationError('Enter a date.');
            return;
        }
        if (!Number.isFinite(parsedAmount) || parsedAmount <= 0) {
            setValidationError('Enter an amount greater than 0.');
            return;
        }
        setValidationError(null);
        setSaving(true);
        try {
            const saved = await onSubmit({
                date,
                amount: parsedAmount,
                category,
                description: description || undefined,
                frequency,
            });
            if (saved) {
                setDate('');
                setAmount('');
                setDescription('');
                setFrequency('monthly');
            }
        } finally {
            setSaving(false);
        }
    }

    return (
        <div style={cardStyle}>
            <h3 style={{ marginBottom: '1rem' }}>{title}</h3>
            <div style={{ display: 'grid', gap: '0.5rem' }}>
                <input type="date" aria-label="Date" value={date} onChange={(e) => setDate(e.target.value)} style={{ padding: '0.4rem' }} />
                <CurrencyInput placeholder="Amount" aria-label="Amount" value={amount} onChange={setAmount} style={{ padding: '0.4rem' }} />
                <select aria-label="Category" value={category} onChange={(e) => setCategory(e.target.value)} style={{ padding: '0.4rem' }}>
                    {categories.map((c) => (
                        <option key={c.value} value={c.value}>{c.label}</option>
                    ))}
                </select>
                <select aria-label="Frequency" value={frequency} onChange={(e) => setFrequency(e.target.value)} style={{ padding: '0.4rem' }}>
                    <option value="monthly">Monthly</option>
                    <option value="annual">Annual</option>
                </select>
                <input placeholder="Description" aria-label="Description" value={description} onChange={(e) => setDescription(e.target.value)} style={{ padding: '0.4rem' }} />
                {validationError && (
                    <div role="alert" style={{ color: '#d32f2f', fontSize: '0.85rem' }}>{validationError}</div>
                )}
                <button
                    onClick={handleSubmit}
                    disabled={saving}
                    style={{ padding: '0.5rem', background: buttonColor, color: '#fff', border: 'none', borderRadius: '4px', cursor: saving ? 'default' : 'pointer', opacity: saving ? 0.6 : 1 }}
                >
                    {title}
                </button>
            </div>
        </div>
    );
}
