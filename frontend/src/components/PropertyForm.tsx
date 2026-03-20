import { inputStyle, labelStyle } from '../utils/styles';
import CurrencyInput from './CurrencyInput';

interface CostSegAllocations {
    fiveYr: string;
    sevenYr: string;
    fifteenYr: string;
    twentySevenYr: string;
}

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
    showDepreciation: boolean; onShowDepreciationChange: (v: boolean) => void;
    depreciationMethod: string; onDepreciationMethodChange: (v: string) => void;
    inServiceDate: string; onInServiceDateChange: (v: string) => void;
    landValue: string; onLandValueChange: (v: string) => void;
    usefulLifeYears: string; onUsefulLifeYearsChange: (v: string) => void;
    costSegAllocations: CostSegAllocations; onCostSegAllocationsChange: (v: CostSegAllocations) => void;
    bonusDepreciationRate: string; onBonusDepreciationRateChange: (v: string) => void;
    costSegStudyYear: string; onCostSegStudyYearChange: (v: string) => void;
    purchasePriceNum: number;
    onSubmit: () => void;
    onCancel: () => void;
}

export default function PropertyForm(props: Props) {
    const landValueNum = parseFloat(props.landValue) || 0;
    const usefulLifeNum = parseFloat(props.usefulLifeYears) || 0;
    const depreciableBasis = props.purchasePriceNum - landValueNum;
    const annualDepreciation = usefulLifeNum > 0 ? depreciableBasis / usefulLifeNum : 0;
    const showDepreciationWarning = landValueNum >= props.purchasePriceNum && props.purchasePriceNum > 0;

    const isCostSeg = props.depreciationMethod === 'cost_segregation';
    const costSegSum = (parseFloat(props.costSegAllocations.fiveYr) || 0)
        + (parseFloat(props.costSegAllocations.sevenYr) || 0)
        + (parseFloat(props.costSegAllocations.fifteenYr) || 0)
        + (parseFloat(props.costSegAllocations.twentySevenYr) || 0);
    const costSegMismatch = isCostSeg && depreciableBasis > 0 && Math.abs(costSegSum - depreciableBasis) > 0.01;
    const bonusRateNum = parseFloat(props.bonusDepreciationRate) || 0;
    const bonusEligibleTotal = (parseFloat(props.costSegAllocations.fiveYr) || 0)
        + (parseFloat(props.costSegAllocations.sevenYr) || 0)
        + (parseFloat(props.costSegAllocations.fifteenYr) || 0);
    const year1Bonus = bonusEligibleTotal * (bonusRateNum / 100);

    function autoFillStructural(updated: CostSegAllocations) {
        const shortLivedSum = (parseFloat(updated.fiveYr) || 0)
            + (parseFloat(updated.sevenYr) || 0)
            + (parseFloat(updated.fifteenYr) || 0);
        const remainder = Math.max(0, depreciableBasis - shortLivedSum);
        const rounded = Math.round(remainder * 100) / 100;
        props.onCostSegAllocationsChange({ ...updated, twentySevenYr: rounded > 0 ? String(rounded) : '' });
    }

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
                    <CurrencyInput value={props.purchasePrice} onChange={props.onPurchasePriceChange} style={inputStyle} />
                </div>
                <div>
                    <label style={labelStyle}>Purchase Date</label>
                    <input type="date" value={props.purchaseDate} onChange={(e) => props.onPurchaseDateChange(e.target.value)} style={inputStyle} />
                </div>
                <div>
                    <label style={labelStyle}>Current Value</label>
                    <CurrencyInput value={props.currentValue} onChange={props.onCurrentValueChange} style={inputStyle} />
                </div>
                <div>
                    <label style={labelStyle}>Mortgage Balance</label>
                    <CurrencyInput value={props.mortgageBalance} onChange={props.onMortgageBalanceChange} style={inputStyle} />
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
                            <CurrencyInput value={props.loanAmount} onChange={props.onLoanAmountChange} style={inputStyle} />
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
                            <CurrencyInput value={props.annualPropertyTax} onChange={props.onAnnualPropertyTaxChange} style={inputStyle} />
                        </div>
                        <div>
                            <label style={labelStyle}>Annual Insurance Cost ($)</label>
                            <CurrencyInput value={props.annualInsuranceCost} onChange={props.onAnnualInsuranceCostChange} style={inputStyle} />
                        </div>
                        <div>
                            <label style={labelStyle}>Annual Maintenance Cost ($)</label>
                            <CurrencyInput value={props.annualMaintenanceCost} onChange={props.onAnnualMaintenanceCostChange} style={inputStyle} />
                        </div>
                    </div>
                </div>
            )}

            <div style={{ marginTop: '1rem' }}>
                <button
                    onClick={() => props.onShowDepreciationChange(!props.showDepreciation)}
                    style={{ padding: '0.4rem 0.8rem', background: 'none', border: '1px solid #999', borderRadius: '4px', cursor: 'pointer', fontSize: '0.9rem' }}
                >
                    {props.showDepreciation ? 'Hide' : 'Show'} Depreciation
                </button>
            </div>

            {props.showDepreciation && (
                <div style={{ marginTop: '1rem', padding: '1rem', background: '#f5f5f5', borderRadius: '8px' }}>
                    <h4 style={{ marginBottom: '0.75rem', fontSize: '0.95rem' }}>Depreciation</h4>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem' }}>
                        <div>
                            <label style={labelStyle}>Depreciation Method</label>
                            <select value={props.depreciationMethod} onChange={(e) => {
                                const newMethod = e.target.value;
                                props.onDepreciationMethodChange(newMethod);
                                if (newMethod !== 'none' && !props.inServiceDate && props.purchaseDate) {
                                    props.onInServiceDateChange(props.purchaseDate);
                                }
                            }} style={inputStyle}>
                                <option value="none">None</option>
                                <option value="straight_line">Straight-Line</option>
                                <option value="cost_segregation">Cost Segregation</option>
                            </select>
                        </div>
                    </div>
                    {props.depreciationMethod !== 'none' && (
                        <>
                            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem', marginTop: '1rem' }}>
                                <div>
                                    <label style={labelStyle}>In-Service Date</label>
                                    <input type="date" value={props.inServiceDate} onChange={(e) => props.onInServiceDateChange(e.target.value)} style={inputStyle} />
                                    {props.inServiceDate === props.purchaseDate && props.purchaseDate && (
                                        <div style={{ fontSize: '0.8rem', color: '#888', marginTop: '0.25rem' }}>Defaulted to purchase date</div>
                                    )}
                                </div>
                                <div>
                                    <label style={labelStyle}>Land Value ($)</label>
                                    <CurrencyInput value={props.landValue} onChange={props.onLandValueChange} style={inputStyle} />
                                </div>
                                {!isCostSeg && (
                                    <div>
                                        <label style={labelStyle}>Useful Life (years)</label>
                                        <input type="number" step="0.5" value={props.usefulLifeYears} onChange={(e) => props.onUsefulLifeYearsChange(e.target.value)} style={inputStyle} />
                                        {props.usefulLifeYears !== '' && parseFloat(props.usefulLifeYears) <= 0 && (
                                            <div style={{ fontSize: '0.8rem', color: '#d32f2f', marginTop: '0.25rem' }}>Useful life must be greater than 0</div>
                                        )}
                                    </div>
                                )}
                            </div>

                            {isCostSeg ? (
                                <>
                                    <h5 style={{ marginTop: '1rem', marginBottom: '0.5rem', fontSize: '0.9rem' }}>Asset Class Allocations</h5>
                                    <div style={{ fontSize: '0.85rem', color: '#555', marginBottom: '0.75rem', padding: '0.5rem 0.75rem', background: '#e3f2fd', borderRadius: '6px' }}>
                                        <strong>Depreciable Basis: ${depreciableBasis.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}</strong>
                                        <span style={{ marginLeft: '0.5rem', color: '#666' }}>(purchase price minus land value)</span>
                                    </div>
                                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem' }}>
                                        <div>
                                            <label style={labelStyle}>5-Year Property ($)</label>
                                            <CurrencyInput value={props.costSegAllocations.fiveYr} onChange={v => {
                                                const updated = { ...props.costSegAllocations, fiveYr: v };
                                                autoFillStructural(updated);
                                            }} style={inputStyle} />
                                            <div style={{ fontSize: '0.75rem', color: '#888', marginTop: '0.15rem' }}>Appliances, carpeting, fixtures</div>
                                        </div>
                                        <div>
                                            <label style={labelStyle}>7-Year Property ($)</label>
                                            <CurrencyInput value={props.costSegAllocations.sevenYr} onChange={v => {
                                                const updated = { ...props.costSegAllocations, sevenYr: v };
                                                autoFillStructural(updated);
                                            }} style={inputStyle} />
                                            <div style={{ fontSize: '0.75rem', color: '#888', marginTop: '0.15rem' }}>Office furniture, equipment</div>
                                        </div>
                                        <div>
                                            <label style={labelStyle}>15-Year Property ($)</label>
                                            <CurrencyInput value={props.costSegAllocations.fifteenYr} onChange={v => {
                                                const updated = { ...props.costSegAllocations, fifteenYr: v };
                                                autoFillStructural(updated);
                                            }} style={inputStyle} />
                                            <div style={{ fontSize: '0.75rem', color: '#888', marginTop: '0.15rem' }}>Land improvements, landscaping, fencing</div>
                                        </div>
                                        <div>
                                            <label style={labelStyle}>27.5-Year Structural ($)</label>
                                            <CurrencyInput value={props.costSegAllocations.twentySevenYr} onChange={v => props.onCostSegAllocationsChange({ ...props.costSegAllocations, twentySevenYr: v })} style={inputStyle} />
                                            <div style={{ fontSize: '0.75rem', color: '#1976d2', marginTop: '0.15rem' }}>Auto-computed as remainder — edit to override</div>
                                        </div>
                                    </div>
                                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem', marginTop: '1rem' }}>
                                        <div>
                                            <label style={labelStyle}>Bonus Depreciation Rate (%)</label>
                                            <input type="number" step="1" min="0" max="100" value={props.bonusDepreciationRate} onChange={(e) => props.onBonusDepreciationRateChange(e.target.value)} style={inputStyle} />
                                        </div>
                                        <div>
                                            <label style={labelStyle}>Study Year (optional)</label>
                                            <input type="number" placeholder="e.g. 2024" value={props.costSegStudyYear} onChange={(e) => props.onCostSegStudyYearChange(e.target.value)} style={inputStyle} />
                                            <div style={{ fontSize: '0.75rem', color: '#888', marginTop: '0.15rem' }}>If later than in-service year, triggers 481(a) catch-up</div>
                                        </div>
                                    </div>
                                    <div style={{ marginTop: '1rem', padding: '0.75rem', background: costSegMismatch ? '#fff3e0' : '#e8f5e9', borderRadius: '6px', fontSize: '0.9rem' }}>
                                        {showDepreciationWarning ? (
                                            <div style={{ color: '#d32f2f', fontWeight: 600 }}>Land value must be less than purchase price for depreciation.</div>
                                        ) : (
                                            <>
                                                {costSegMismatch && (
                                                    <div style={{ color: '#e65100', fontWeight: 600, marginBottom: '0.5rem' }}>
                                                        Allocations total (${costSegSum.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}) does not equal depreciable basis (${depreciableBasis.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })})
                                                    </div>
                                                )}
                                                <div><strong>Year-1 Bonus Deduction:</strong> ${year1Bonus.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}</div>
                                                <div><strong>Annual Structural Depreciation:</strong> ${((parseFloat(props.costSegAllocations.twentySevenYr) || 0) / 27.5).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}</div>
                                            </>
                                        )}
                                    </div>
                                </>
                            ) : (
                                <div style={{ marginTop: '1rem', padding: '0.75rem', background: '#e8f5e9', borderRadius: '6px', fontSize: '0.9rem' }}>
                                    {showDepreciationWarning ? (
                                        <div style={{ color: '#d32f2f', fontWeight: 600 }}>Land value must be less than purchase price for depreciation.</div>
                                    ) : (
                                        <>
                                            <div><strong>Depreciable Basis:</strong> ${depreciableBasis.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}</div>
                                            <div><strong>Annual Depreciation:</strong> ${annualDepreciation.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}</div>
                                        </>
                                    )}
                                </div>
                            )}
                        </>
                    )}
                </div>
            )}

            <div style={{ marginTop: '1rem', display: 'flex', gap: '0.5rem' }}>
                <button onClick={props.onSubmit} style={{ padding: '0.5rem 1rem', background: '#2e7d32', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>{props.submitLabel}</button>
                <button onClick={props.onCancel} style={{ padding: '0.5rem 1rem', background: '#eee', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>Cancel</button>
            </div>
        </div>
    );
}
