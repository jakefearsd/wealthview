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

function validate(date: string, amount: number, quantity: number | undefined): string | null {
    if (date === '') return 'Date is required.';
    if (!Number.isFinite(amount)) return 'Amount must be a number.';
    if (quantity !== undefined && (!Number.isFinite(quantity) || quantity < 0)) {
        return 'Quantity must be zero or more.';
    }
    return null;
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

    const parsedAmount = parseFloat(txnAmount);
    const parsedQuantity = txnQuantity.trim() === '' ? undefined : parseFloat(txnQuantity);
    const validationError = validate(txnDate, parsedAmount, parsedQuantity);
    const dirty = txnDate !== '' || txnAmount !== '' || txnQuantity !== '' || txnSymbol !== '';

    function handleSubmit() {
        if (validationError) return;
        const request: TransactionRequest = {
            date: txnDate,
            type: txnType,
            // Holdings and prices are keyed by upper-case ticker; "aapl " would otherwise create a phantom holding.
            symbol: txnSymbol.trim().toUpperCase() || undefined,
            quantity: parsedQuantity,
            amount: parsedAmount,
        };
        void saveMutation.mutate(request);
    }

    return (
        <div style={{ padding: '1rem', background: '#f9f9f9', borderRadius: '4px', marginBottom: '1rem' }}>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: '0.5rem' }}>
                <input type="date" aria-label="Date" value={txnDate} onChange={(e) => setTxnDate(e.target.value)} style={{ padding: '0.4rem' }} />
                <select aria-label="Type" value={txnType} onChange={(e) => setTxnType(e.target.value)} style={{ padding: '0.4rem' }}>
                    <option value="buy">Buy</option>
                    <option value="sell">Sell</option>
                    <option value="dividend">Dividend</option>
                    <option value="deposit">Deposit</option>
                    <option value="withdrawal">Withdrawal</option>
                </select>
                <input placeholder="Symbol" aria-label="Symbol" value={txnSymbol} onChange={(e) => setTxnSymbol(e.target.value)} style={{ padding: '0.4rem' }} />
                <input placeholder="Quantity" aria-label="Quantity" type="number" min="0" step="any" value={txnQuantity} onChange={(e) => setTxnQuantity(e.target.value)} style={{ padding: '0.4rem' }} />
                <CurrencyInput placeholder="Amount" aria-label="Amount" value={txnAmount} onChange={setTxnAmount} style={{ padding: '0.4rem' }} />
            </div>
            {dirty && validationError && (
                <div role="alert" style={{ color: '#c62828', fontSize: '0.85rem', marginTop: '0.5rem' }}>{validationError}</div>
            )}
            <div style={{ marginTop: '0.5rem', display: 'flex', gap: '0.5rem' }}>
                <button
                    onClick={handleSubmit}
                    disabled={validationError !== null}
                    style={{ padding: '0.4rem 0.8rem', background: '#2e7d32', color: '#fff', border: 'none', borderRadius: '4px', cursor: validationError ? 'not-allowed' : 'pointer', opacity: validationError ? 0.5 : 1 }}
                >Save</button>
                <button onClick={onCancel} style={{ padding: '0.4rem 0.8rem', background: '#eee', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>Cancel</button>
            </div>
        </div>
    );
}
