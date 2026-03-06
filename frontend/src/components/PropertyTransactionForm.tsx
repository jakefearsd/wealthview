import { useState } from 'react';
import { cardStyle } from '../utils/styles';

interface CategoryOption {
    value: string;
    label: string;
}

interface Props {
    title: string;
    categories: CategoryOption[];
    onSubmit: (data: { date: string; amount: number; category: string; description?: string }) => Promise<void>;
    buttonColor: string;
}

export default function PropertyTransactionForm({ title, categories, onSubmit, buttonColor }: Props) {
    const [date, setDate] = useState('');
    const [amount, setAmount] = useState('');
    const [category, setCategory] = useState(categories[0]?.value ?? '');
    const [description, setDescription] = useState('');

    async function handleSubmit() {
        await onSubmit({
            date,
            amount: parseFloat(amount),
            category,
            description: description || undefined,
        });
        setDate('');
        setAmount('');
        setDescription('');
    }

    return (
        <div style={cardStyle}>
            <h3 style={{ marginBottom: '1rem' }}>{title}</h3>
            <div style={{ display: 'grid', gap: '0.5rem' }}>
                <input type="date" value={date} onChange={(e) => setDate(e.target.value)} style={{ padding: '0.4rem' }} />
                <input type="number" step="0.01" placeholder="Amount" value={amount} onChange={(e) => setAmount(e.target.value)} style={{ padding: '0.4rem' }} />
                <select value={category} onChange={(e) => setCategory(e.target.value)} style={{ padding: '0.4rem' }}>
                    {categories.map((c) => (
                        <option key={c.value} value={c.value}>{c.label}</option>
                    ))}
                </select>
                <input placeholder="Description" value={description} onChange={(e) => setDescription(e.target.value)} style={{ padding: '0.4rem' }} />
                <button onClick={handleSubmit} style={{ padding: '0.5rem', background: buttonColor, color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>{title}</button>
            </div>
        </div>
    );
}
