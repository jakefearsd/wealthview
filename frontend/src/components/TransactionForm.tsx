import { useState } from 'react';
import { createTransaction, updateTransaction } from '../api/transactions';
import { useApiMutation } from '../hooks/useApiMutation';
import CurrencyInput from './CurrencyInput';
import type { Transaction, TransactionRequest } from '../types/transaction';

interface Props {
    accountId: string;
    onSuccess: () => void;
    onCancel: () => void;
    initialValues?: Transaction;
}

export default function TransactionForm({ accountId, onSuccess, onCancel, initialValues }: Props) {
    const [txnDate, setTxnDate] = useState(initialValues?.date ?? '');
    const [txnType, setTxnType] = useState(initialValues?.type ?? 'buy');
    const [txnSymbol, setTxnSymbol] = useState(initialValues?.symbol ?? '');
    const [txnQuantity, setTxnQuantity] = useState(initialValues?.quantity != null ? String(initialValues.quantity) : '');
    const [txnAmount, setTxnAmount] = useState(initialValues ? String(initialValues.amount) : '');

    const saveMutation = useApiMutation(
        (request: TransactionRequest) => (
            initialValues ? updateTransaction(initialValues.id, request) : createTransaction(accountId, request)
        ),
        {
            successMessage: initialValues ? 'Transaction updated' : 'Transaction added',
            onSuccess: () => onSuccess(),
        },
    );

    function handleSubmit() {
        const request: TransactionRequest = {
            date: txnDate,
            type: txnType,
            symbol: txnSymbol || undefined,
            quantity: txnQuantity ? parseFloat(txnQuantity) : undefined,
            amount: parseFloat(txnAmount),
        };
        void saveMutation.mutate(request);
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
                <CurrencyInput placeholder="Amount" value={txnAmount} onChange={setTxnAmount} style={{ padding: '0.4rem' }} />
            </div>
            <div style={{ marginTop: '0.5rem', display: 'flex', gap: '0.5rem' }}>
                <button onClick={handleSubmit} style={{ padding: '0.4rem 0.8rem', background: '#2e7d32', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>Save</button>
                <button onClick={onCancel} style={{ padding: '0.4rem 0.8rem', background: '#eee', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>Cancel</button>
            </div>
        </div>
    );
}
