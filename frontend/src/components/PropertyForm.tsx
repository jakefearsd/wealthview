import { inputStyle, labelStyle } from '../utils/styles';
import CurrencyInput from './CurrencyInput';

export interface CostSegAllocations {
    fiveYr: string;
    sevenYr: string;
    fifteenYr: string;
    twentySevenYr: string;
}

export interface PropertyFormValues {
    address: string;
    purchasePrice: string;
    purchaseDate: string;
    currentValue: string;
    mortgageBalance: string;
    propertyType: string;
    showLoanDetails: boolean;
    loanAmount: string;
    annualInterestRate: string;
    loanTermMonths: string;
    loanStartDate: string;
    useComputedBalance: boolean;
    showFinancialAssumptions: boolean;
    annualAppreciationRate: string;
    annualPropertyTax: string;
    annualInsuranceCost: string;
    annualMaintenanceCost: string;
    showDepreciation: boolean;
    depreciationMethod: string;
    inServiceDate: string;
    landValue: string;
    usefulLifeYears: string;
    costSegAllocations: CostSegAllocations;
    bonusDepreciationRate: string;
    costSegStudyYear: string;
}

interface Props {
    heading: string;
    submitLabel: string;
    values: PropertyFormValues;
    onChange: (patch: Partial<PropertyFormValues>) => void;
    purchasePriceNum: number;
    onSubmit: () => void;
    onCancel: () => void;
}

export default function PropertyForm({ heading, submitLabel, values, onChange, purchasePriceNum, onSubmit, onCancel }: Props) {
    const landValueNum = parseFloat(values.landValue) || 0;
    const usefulLifeNum = parseFloat(values.usefulLifeYears) || 0;
    const depreciableBasis = purchasePriceNum - landValueNum;
    const annualDepreciation = usefulLifeNum > 0 ? depreciableBasis / usefulLifeNum : 0;
    const showDepreciationWarning = landValueNum >= purchasePriceNum && purchasePriceNum > 0;

    const isCostSeg = values.depreciationMethod === 'cost_segregation';
    const costSegSum = (parseFloat(values.costSegAllocations.fiveYr) || 0)
        + (parseFloat(values.costSegAllocations.sevenYr) || 0)
        + (parseFloat(values.costSegAllocations.fifteenYr) || 0)
        + (parseFloat(values.costSegAllocations.twentySevenYr) || 0);
    const costSegMismatch = isCostSeg && depreciableBasis > 0 && Math.abs(costSegSum - depreciableBasis) > 0.01;
    const bonusRateNum = parseFloat(values.bonusDepreciationRate) || 0;
    const bonusEligibleTotal = (parseFloat(values.costSegAllocations.fiveYr) || 0)
        + (parseFloat(values.costSegAllocations.sevenYr) || 0)
        + (parseFloat(values.costSegAllocations.fifteenYr) || 0);
    const year1Bonus = bonusEligibleTotal * (bonusRateNum / 100);

    function updateAllocations(allocs: CostSegAllocations) {
        onChange({ costSegAllocations: allocs });
    }

    function autoFillStructural(updated: CostSegAllocations) {
        const shortLivedSum = (parseFloat(updated.fiveYr) || 0)
            + (parseFloat(updated.sevenYr) || 0)
            + (parseFloat(updated.fifteenYr) || 0);
        const remainder = Math.max(0, depreciableBasis - shortLivedSum);
        const rounded = Math.round(remainder * 100) / 100;
        updateAllocations({ ...updated, twentySevenYr: rounded > 0 ? String(rounded) : '' });
    }

    return (
        <div style={{ background: '#fff', padding: '1.5rem', borderRadius: '8px', marginBottom: '1.5rem', boxShadow: '0 1px 3px rgba(0,0,0,0.1)' }}>
            <h3 style={{ marginBottom: '1rem' }}>{heading}</h3>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem' }}>
                <div>
                    <label style={labelStyle}>Address</label>
                    <input placeholder="123 Main St" value={values.address} onChange={(e) => onChange({ address: e.target.value })} style={inputStyle} />
                </div>
                <div>
                    <label style={labelStyle}>Purchase Price</label>
                    <CurrencyInput value={values.purchasePrice} onChange={(v) => onChange({ purchasePrice: v })} style={inputStyle} />
                </div>
                <div>
                    <label style={labelStyle}>Purchase Date</label>
                    <input type="date" value={values.purchaseDate} onChange={(e) => onChange({ purchaseDate: e.target.value })} style={inputStyle} />
                </div>
                <div>
                    <label style={labelStyle}>Current Value</label>
                    <CurrencyInput value={values.currentValue} onChange={(v) => onChange({ currentValue: v })} style={inputStyle} />
                </div>
                <div>
                    <label style={labelStyle}>Mortgage Balance</label>
                    <CurrencyInput value={values.mortgageBalance} onChange={(v) => onChange({ mortgageBalance: v })} style={inputStyle} />
                </div>
                <div>
                    <label style={labelStyle}>Property Type</label>
                    <select value={values.propertyType} onChange={(e) => onChange({ propertyType: e.target.value })} style={inputStyle}>
                        <option value="primary_residence">Primary Residence</option>
                        <option value="investment">Investment</option>
                        <option value="vacation">Vacation</option>
                    </select>
                </div>
            </div>

            <div style={{ marginTop: '1rem' }}>
                <button
                    onClick={() => onChange({ showLoanDetails: !values.showLoanDetails })}
                    style={{ padding: '0.4rem 0.8rem', background: 'none', border: '1px solid #999', borderRadius: '4px', cursor: 'pointer', fontSize: '0.9rem' }}
                >
                    {values.showLoanDetails ? 'Hide' : 'Show'} Loan Details
                </button>
            </div>

            {values.showLoanDetails && (
                <div style={{ marginTop: '1rem', padding: '1rem', background: '#f5f5f5', borderRadius: '8px' }}>
                    <h4 style={{ marginBottom: '0.75rem', fontSize: '0.95rem' }}>Loan Details</h4>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem' }}>
                        <div>
                            <label style={labelStyle}>Loan Amount</label>
                            <CurrencyInput value={values.loanAmount} onChange={(v) => onChange({ loanAmount: v })} style={inputStyle} />
                        </div>
                        <div>
                            <label style={labelStyle}>Annual Interest Rate (%)</label>
                            <input type="number" step="0.01" value={values.annualInterestRate} onChange={(e) => onChange({ annualInterestRate: e.target.value })} style={inputStyle} />
                        </div>
                        <div>
                            <label style={labelStyle}>Loan Term (months)</label>
                            <input type="number" value={values.loanTermMonths} onChange={(e) => onChange({ loanTermMonths: e.target.value })} style={inputStyle} />
                        </div>
                        <div>
                            <label style={labelStyle}>Loan Start Date</label>
                            <input type="date" value={values.loanStartDate} onChange={(e) => onChange({ loanStartDate: e.target.value })} style={inputStyle} />
                        </div>
                    </div>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginTop: '0.75rem', fontSize: '0.9rem' }}>
                        <input type="checkbox" checked={values.useComputedBalance} onChange={(e) => onChange({ useComputedBalance: e.target.checked })} />
                        Use computed mortgage balance (amortization)
                    </label>
                </div>
            )}

            <div style={{ marginTop: '1rem' }}>
                <button
                    onClick={() => onChange({ showFinancialAssumptions: !values.showFinancialAssumptions })}
                    style={{ padding: '0.4rem 0.8rem', background: 'none', border: '1px solid #999', borderRadius: '4px', cursor: 'pointer', fontSize: '0.9rem' }}
                >
                    {values.showFinancialAssumptions ? 'Hide' : 'Show'} Financial Assumptions
                </button>
            </div>

            {values.showFinancialAssumptions && (
                <div style={{ marginTop: '1rem', padding: '1rem', background: '#f5f5f5', borderRadius: '8px' }}>
                    <h4 style={{ marginBottom: '0.75rem', fontSize: '0.95rem' }}>Financial Assumptions</h4>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem' }}>
                        <div>
                            <label style={labelStyle}>Annual Appreciation Rate (%)</label>
                            <input type="number" step="0.1" placeholder="e.g. 3.0" value={values.annualAppreciationRate} onChange={(e) => onChange({ annualAppreciationRate: e.target.value })} style={inputStyle} />
                        </div>
                        <div>
                            <label style={labelStyle}>Annual Property Tax ($)</label>
                            <CurrencyInput value={values.annualPropertyTax} onChange={(v) => onChange({ annualPropertyTax: v })} style={inputStyle} />
                        </div>
                        <div>
                            <label style={labelStyle}>Annual Insurance Cost ($)</label>
                            <CurrencyInput value={values.annualInsuranceCost} onChange={(v) => onChange({ annualInsuranceCost: v })} style={inputStyle} />
                        </div>
                        <div>
                            <label style={labelStyle}>Annual Maintenance Cost ($)</label>
                            <CurrencyInput value={values.annualMaintenanceCost} onChange={(v) => onChange({ annualMaintenanceCost: v })} style={inputStyle} />
                        </div>
                    </div>
                </div>
            )}

            <div style={{ marginTop: '1rem' }}>
                <button
                    onClick={() => onChange({ showDepreciation: !values.showDepreciation })}
                    style={{ padding: '0.4rem 0.8rem', background: 'none', border: '1px solid #999', borderRadius: '4px', cursor: 'pointer', fontSize: '0.9rem' }}
                >
                    {values.showDepreciation ? 'Hide' : 'Show'} Depreciation
                </button>
            </div>

            {values.showDepreciation && (
                <div style={{ marginTop: '1rem', padding: '1rem', background: '#f5f5f5', borderRadius: '8px' }}>
                    <h4 style={{ marginBottom: '0.75rem', fontSize: '0.95rem' }}>Depreciation</h4>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem' }}>
                        <div>
                            <label style={labelStyle}>Depreciation Method</label>
                            <select value={values.depreciationMethod} onChange={(e) => {
                                const newMethod = e.target.value;
                                const patch: Partial<PropertyFormValues> = { depreciationMethod: newMethod };
                                if (newMethod !== 'none' && !values.inServiceDate && values.purchaseDate) {
                                    patch.inServiceDate = values.purchaseDate;
                                }
                                onChange(patch);
                            }} style={inputStyle}>
                                <option value="none">None</option>
                                <option value="straight_line">Straight-Line</option>
                                <option value="cost_segregation">Cost Segregation</option>
                            </select>
                        </div>
                    </div>
                    {values.depreciationMethod !== 'none' && (
                        <>
                            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem', marginTop: '1rem' }}>
                                <div>
                                    <label style={labelStyle}>In-Service Date</label>
                                    <input type="date" value={values.inServiceDate} onChange={(e) => onChange({ inServiceDate: e.target.value })} style={inputStyle} />
                                    {values.inServiceDate === values.purchaseDate && values.purchaseDate && (
                                        <div style={{ fontSize: '0.8rem', color: '#888', marginTop: '0.25rem' }}>Defaulted to purchase date</div>
                                    )}
                                </div>
                                <div>
                                    <label style={labelStyle}>Land Value ($)</label>
                                    <CurrencyInput value={values.landValue} onChange={(v) => onChange({ landValue: v })} style={inputStyle} />
                                </div>
                                {!isCostSeg && (
                                    <div>
                                        <label style={labelStyle}>Useful Life (years)</label>
                                        <input type="number" step="0.5" value={values.usefulLifeYears} onChange={(e) => onChange({ usefulLifeYears: e.target.value })} style={inputStyle} />
                                        {values.usefulLifeYears !== '' && parseFloat(values.usefulLifeYears) <= 0 && (
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
                                            <CurrencyInput value={values.costSegAllocations.fiveYr} onChange={v => autoFillStructural({ ...values.costSegAllocations, fiveYr: v })} style={inputStyle} />
                                            <div style={{ fontSize: '0.75rem', color: '#888', marginTop: '0.15rem' }}>Appliances, carpeting, fixtures</div>
                                        </div>
                                        <div>
                                            <label style={labelStyle}>7-Year Property ($)</label>
                                            <CurrencyInput value={values.costSegAllocations.sevenYr} onChange={v => autoFillStructural({ ...values.costSegAllocations, sevenYr: v })} style={inputStyle} />
                                            <div style={{ fontSize: '0.75rem', color: '#888', marginTop: '0.15rem' }}>Office furniture, equipment</div>
                                        </div>
                                        <div>
                                            <label style={labelStyle}>15-Year Property ($)</label>
                                            <CurrencyInput value={values.costSegAllocations.fifteenYr} onChange={v => autoFillStructural({ ...values.costSegAllocations, fifteenYr: v })} style={inputStyle} />
                                            <div style={{ fontSize: '0.75rem', color: '#888', marginTop: '0.15rem' }}>Land improvements, landscaping, fencing</div>
                                        </div>
                                        <div>
                                            <label style={labelStyle}>27.5-Year Structural ($)</label>
                                            <CurrencyInput value={values.costSegAllocations.twentySevenYr} onChange={v => updateAllocations({ ...values.costSegAllocations, twentySevenYr: v })} style={inputStyle} />
                                            <div style={{ fontSize: '0.75rem', color: '#1976d2', marginTop: '0.15rem' }}>Auto-computed as remainder — edit to override</div>
                                        </div>
                                    </div>
                                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem', marginTop: '1rem' }}>
                                        <div>
                                            <label style={labelStyle}>Bonus Depreciation Rate (%)</label>
                                            <input type="number" step="1" min="0" max="100" value={values.bonusDepreciationRate} onChange={(e) => onChange({ bonusDepreciationRate: e.target.value })} style={inputStyle} />
                                        </div>
                                        <div>
                                            <label style={labelStyle}>Study Year (optional)</label>
                                            <input type="number" placeholder="e.g. 2024" value={values.costSegStudyYear} onChange={(e) => onChange({ costSegStudyYear: e.target.value })} style={inputStyle} />
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
                                                <div><strong>Annual Structural Depreciation:</strong> ${((parseFloat(values.costSegAllocations.twentySevenYr) || 0) / 27.5).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}</div>
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
                <button onClick={onSubmit} style={{ padding: '0.5rem 1rem', background: '#2e7d32', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>{submitLabel}</button>
                <button onClick={onCancel} style={{ padding: '0.5rem 1rem', background: '#eee', border: 'none', borderRadius: '4px', cursor: 'pointer' }}>Cancel</button>
            </div>
        </div>
    );
}
