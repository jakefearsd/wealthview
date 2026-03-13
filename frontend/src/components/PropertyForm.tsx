import { formatCurrencyInput, parseCurrencyInput } from '../utils/format';
import { inputStyle, labelStyle } from '../utils/styles';

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
    showFinancialAssumptions: boolean; onShowFinancialAssumptionsChange: (v: boolean) => void;
    annualAppreciationRate: string; onAnnualAppreciationRateChange: (v: string) => void;
    annualPropertyTax: string; onAnnualPropertyTaxChange: (v: string) => void;
    annualInsuranceCost: string; onAnnualInsuranceCostChange: (v: string) => void;
    annualMaintenanceCost: string; onAnnualMaintenanceCostChange: (v: string) => void;
    onSubmit: () => void;
    onCancel: () => void;
}

export default function PropertyForm(props: Props) {
    return (
        <div style={{ background: '#fff', padding: '1.5rem', borderRadius: '8px', marginBottom: '1.5rem', boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
            <h3 style={{ marginBottom: '1rem' }}>{props.heading}</h3>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem' }}>
                <div>
                    <label style={labelStyle}>Address</label>
                    <input placeholder="123 Main St" value={props.address} onChange={(e) => props.onAddressChange(e.target.value)} style={inputStyle} />
                </div>
                <div>
                    <label style={labelStyle}>Purchase Price</label>
                    <input type="text" inputMode="decimal" value={formatCurrencyInput(props.purchasePrice)} onChange={(e) => props.onPurchasePriceChange(parseCurrencyInput(e.target.value))} style={inputStyle} />
                </div>
                <div>
                    <label style={labelStyle}>Purchase Date</label>
                    <input type="date" value={props.purchaseDate} onChange={(e) => props.onPurchaseDateChange(e.target.value)} style={inputStyle} />
                </div>
                <div>
                    <label style={labelStyle}>Current Value</label>
                    <input type="text" inputMode="decimal" value={formatCurrencyInput(props.currentValue)} onChange={(e) => props.onCurrentValueChange(parseCurrencyInput(e.target.value))} style={inputStyle} />
                </div>
                <div>
                    <label style={labelStyle}>Mortgage Balance</label>
                    <input type="text" inputMode="decimal" value={formatCurrencyInput(props.mortgageBalance)} onChange={(e) => props.onMortgageBalanceChange(parseCurrencyInput(e.target.value))} style={inputStyle} />
                </div>
                <div>
                    <label style={labelStyle}>Property Type</label>
                    <select value={props.propertyType} onChange={(e) => props.onPropertyTypeChange(e.target.value)} style={inputStyle}>
                        <option value="primary_residence">Primary Residence</option>
                        <option value="investment">Investment</option>
                        <option value="vacation">Vacation</option>
                    </select>
                </div>
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
                        <div>
                            <label style={labelStyle}>Loan Amount</label>
                            <input type="text" inputMode="decimal" value={formatCurrencyInput(props.loanAmount)} onChange={(e) => props.onLoanAmountChange(parseCurrencyInput(e.target.value))} style={inputStyle} />
                        </div>
                        <div>
                            <label style={labelStyle}>Annual Interest Rate (%)</label>
                            <input type="number" step="0.01" value={props.annualInterestRate} onChange={(e) => props.onAnnualInterestRateChange(e.target.value)} style={inputStyle} />
                        </div>
                        <div>
                            <label style={labelStyle}>Loan Term (months)</label>
                            <input type="number" value={props.loanTermMonths} onChange={(e) => props.onLoanTermMonthsChange(e.target.value)} style={inputStyle} />
                        </div>
                        <div>
                            <label style={labelStyle}>Loan Start Date</label>
                            <input type="date" value={props.loanStartDate} onChange={(e) => props.onLoanStartDateChange(e.target.value)} style={inputStyle} />
                        </div>
                    </div>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginTop: '0.75rem', fontSize: '0.9rem' }}>
                        <input type="checkbox" checked={props.useComputedBalance} onChange={(e) => props.onUseComputedBalanceChange(e.target.checked)} />
                        Use computed mortgage balance (amortization)
                    </label>
                </div>
            )}

            <div style={{ marginTop: '1rem' }}>
                <button
                    onClick={() => props.onShowFinancialAssumptionsChange(!props.showFinancialAssumptions)}
                    style={{ padding: '0.4rem 0.8rem', background: 'none', border: '1px solid #999', borderRadius: '4px', cursor: 'pointer', fontSize: '0.9rem' }}
                >
                    {props.showFinancialAssumptions ? 'Hide' : 'Show'} Financial Assumptions
                </button>
            </div>

            {props.showFinancialAssumptions && (
                <div style={{ marginTop: '1rem', padding: '1rem', background: '#f5f5f5', borderRadius: '8px' }}>
                    <h4 style={{ marginBottom: '0.75rem', fontSize: '0.95rem' }}>Financial Assumptions</h4>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem' }}>
                        <div>
                            <label style={labelStyle}>Annual Appreciation Rate (%)</label>
                            <input type="number" step="0.1" placeholder="e.g. 3.0" value={props.annualAppreciationRate} onChange={(e) => props.onAnnualAppreciationRateChange(e.target.value)} style={inputStyle} />
                        </div>
                        <div>
                            <label style={labelStyle}>Annual Property Tax ($)</label>
                            <input type="text" inputMode="decimal" value={formatCurrencyInput(props.annualPropertyTax)} onChange={(e) => props.onAnnualPropertyTaxChange(parseCurrencyInput(e.target.value))} style={inputStyle} />
                        </div>
                        <div>
                            <label style={labelStyle}>Annual Insurance Cost ($)</label>
                            <input type="text" inputMode="decimal" value={formatCurrencyInput(props.annualInsuranceCost)} onChange={(e) => props.onAnnualInsuranceCostChange(parseCurrencyInput(e.target.value))} style={inputStyle} />
                        </div>
                        <div>
                            <label style={labelStyle}>Annual Maintenance Cost ($)</label>
                            <input type="text" inputMode="decimal" value={formatCurrencyInput(props.annualMaintenanceCost)} onChange={(e) => props.onAnnualMaintenanceCostChange(parseCurrencyInput(e.target.value))} style={inputStyle} />
                        </div>
                    </div>
                </div>
            )}

            <div style={{ marginTop: '1rem', display: 'flex', gap: '0.5rem' }}>
                <button onClick={props.onSubmit} style={{ padding: '0.5rem 1rem', background: '#2e7d32', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>{props.submitLabel}</button>
                <button onClick={props.onCancel} style={{ padding: '0.5rem 1rem', background: '#eee', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>Cancel</button>
            </div>
        </div>
    );
}
