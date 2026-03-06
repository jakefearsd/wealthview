import { useState } from 'react';
import { createTransaction } from '../api/transactions';
import type { TransactionRequest } from '../types/transaction';
import toast from 'react-hot-toast';

interface Props {
    accountId: string;
    onSuccess: () => void;
    onCancel: () => void;
}

export default function TransactionForm({ accountId, onSuccess, onCancel }: Props) {
    const [txnDate, setTxnDate] = useState('');
    const [txnType, setTxnType] = useState('buy');
    const [txnSymbol, setTxnSymbol] = useState('');
    const [txnQuantity, setTxnQuantity] = useState('');
    const [txnAmount, setTxnAmount] = useState('');

    async function handleSubmit() {
        const request: TransactionRequest = {
            date: txnDate,
            type: txnType,
            symbol: txnSymbol || undefined,
            quantity: txnQuantity ? parseFloat(txnQuantity) : undefined,
            amount: parseFloat(txnAmount),
        };
        try {
            await createTransaction(accountId, request);
            toast.success('Transaction added');
            onSuccess();
        } catch {
            toast.error('Failed to add transaction');
        }
    }

    return (
        <div style={{ padding: '1rem', background: '#f9f9f9', borderRadius: '4px', marginBottom: '1rem' }}>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: '0.5rem' }}>
                <input type="date" value={txnDate} onChange={(e) => setTxnDate(e.target.value)} style={{ padding: '0.4rem' }} />
                <select value={txnType} onChange={(e) => setTxnType(e.target.value)} style={{ padding: '0.4rem' }}>
                    <option value="buy">Buy</option>
                    <option value="sell">Sell</option>
                    <option value="dividend">Dividend</option>
                    <option value="deposit">Deposit</option>
                    <option value="withdrawal">Withdrawal</option>
                </select>
                <input placeholder="Symbol" value={txnSymbol} onChange={(e) => setTxnSymbol(e.target.value)} style={{ padding: '0.4rem' }} />
                <input placeholder="Quantity" type="number" value={txnQuantity} onChange={(e) => setTxnQuantity(e.target.value)} style={{ padding: '0.4rem' }} />
                <input placeholder="Amount" type="number" step="0.01" value={txnAmount} onChange={(e) => setTxnAmount(e.target.value)} style={{ padding: '0.4rem' }} />
            </div>
            <div style={{ marginTop: '0.5rem', display: 'flex', gap: '0.5rem' }}>
                <button onClick={handleSubmit} style={{ padding: '0.4rem 0.8rem', background: '#2e7d32', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>Save</button>
                <button onClick={onCancel} style={{ padding: '0.4rem 0.8rem', background: '#eee', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>Cancel</button>
            </div>
        </div>
    );
}
