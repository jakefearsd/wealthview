interface Props {
    heading: string;
    submitLabel: string;
    address: string; onAddressChange: (v: string) => void;
    purchasePrice: string; onPurchasePriceChange: (v: string) => void;
    purchaseDate: string; onPurchaseDateChange: (v: string) => void;
    currentValue: string; onCurrentValueChange: (v: string) => void;
    mortgageBalance: string; onMortgageBalanceChange: (v: string) => void;
    propertyType: string; onPropertyTypeChange: (v: string) => void;
    showLoanDetails: boolean; onShowLoanDetailsChange: (v: boolean) => void;
    loanAmount: string; onLoanAmountChange: (v: string) => void;
    annualInterestRate: string; onAnnualInterestRateChange: (v: string) => void;
    loanTermMonths: string; onLoanTermMonthsChange: (v: string) => void;
    loanStartDate: string; onLoanStartDateChange: (v: string) => void;
    useComputedBalance: boolean; onUseComputedBalanceChange: (v: boolean) => void;
    onSubmit: () => void;
    onCancel: () => void;
}

const inputStyle = { padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px' };

export default function PropertyForm(props: Props) {
    return (
        <div style={{ background: '#fff', padding: '1.5rem', borderRadius: '8px', marginBottom: '1.5rem', boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
            <h3 style={{ marginBottom: '1rem' }}>{props.heading}</h3>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem' }}>
                <input placeholder="Address" value={props.address} onChange={(e) => props.onAddressChange(e.target.value)} style={inputStyle} />
                <input placeholder="Purchase Price" type="number" value={props.purchasePrice} onChange={(e) => props.onPurchasePriceChange(e.target.value)} style={inputStyle} />
                <input type="date" value={props.purchaseDate} onChange={(e) => props.onPurchaseDateChange(e.target.value)} style={inputStyle} />
                <input placeholder="Current Value" type="number" value={props.currentValue} onChange={(e) => props.onCurrentValueChange(e.target.value)} style={inputStyle} />
                <input placeholder="Mortgage Balance" type="number" value={props.mortgageBalance} onChange={(e) => props.onMortgageBalanceChange(e.target.value)} style={inputStyle} />
                <select value={props.propertyType} onChange={(e) => props.onPropertyTypeChange(e.target.value)} style={inputStyle}>
                    <option value="primary_residence">Primary Residence</option>
                    <option value="investment">Investment</option>
                    <option value="vacation">Vacation</option>
                </select>
            </div>

            <div style={{ marginTop: '1rem' }}>
                <button
                    onClick={() => props.onShowLoanDetailsChange(!props.showLoanDetails)}
                    style={{ padding: '0.4rem 0.8rem', background: 'none', border: '1px solid #999', borderRadius: '4px', cursor: 'pointer', fontSize: '0.9rem' }}
                >
                    {props.showLoanDetails ? 'Hide' : 'Show'} Loan Details
                </button>
            </div>

            {props.showLoanDetails && (
                <div style={{ marginTop: '1rem', padding: '1rem', background: '#f5f5f5', borderRadius: '8px' }}>
                    <h4 style={{ marginBottom: '0.75rem', fontSize: '0.95rem' }}>Loan Details</h4>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem' }}>
                        <input placeholder="Loan Amount" type="number" value={props.loanAmount} onChange={(e) => props.onLoanAmountChange(e.target.value)} style={inputStyle} />
                        <input placeholder="Annual Interest Rate (%)" type="number" step="0.01" value={props.annualInterestRate} onChange={(e) => props.onAnnualInterestRateChange(e.target.value)} style={inputStyle} />
                        <input placeholder="Loan Term (months)" type="number" value={props.loanTermMonths} onChange={(e) => props.onLoanTermMonthsChange(e.target.value)} style={inputStyle} />
                        <input type="date" value={props.loanStartDate} onChange={(e) => props.onLoanStartDateChange(e.target.value)} style={inputStyle} />
                    </div>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginTop: '0.75rem', fontSize: '0.9rem' }}>
                        <input type="checkbox" checked={props.useComputedBalance} onChange={(e) => props.onUseComputedBalanceChange(e.target.checked)} />
                        Use computed mortgage balance (amortization)
                    </label>
                </div>
            )}

            <div style={{ marginTop: '1rem', display: 'flex', gap: '0.5rem' }}>
                <button onClick={props.onSubmit} style={{ padding: '0.5rem 1rem', background: '#2e7d32', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>{props.submitLabel}</button>
                <button onClick={props.onCancel} style={{ padding: '0.5rem 1rem', background: '#eee', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>Cancel</button>
            </div>
        </div>
    );
}
