import HelpText from './HelpText';
import InfoSection from './InfoSection';
import { formatCurrencyInput, parseCurrencyInput } from '../utils/format';
import { inputStyle, labelStyle } from '../utils/styles';

export interface RothConversionSectionProps {
    rothConversionStrategy: string;
    onRothConversionStrategyChange: (value: string) => void;
    annualRothConversion: number;
    onAnnualRothConversionChange: (value: number) => void;
    targetBracketRate: number;
    onTargetBracketRateChange: (value: number) => void;
    rothConversionStartYear: number | null;
    onRothConversionStartYearChange: (value: number | null) => void;
    filingStatus: string;
    onFilingStatusChange: (value: string) => void;
    otherIncome: number;
    onOtherIncomeChange: (value: number) => void;
}

export default function RothConversionSection({
    rothConversionStrategy,
    onRothConversionStrategyChange,
    annualRothConversion,
    onAnnualRothConversionChange,
    targetBracketRate,
    onTargetBracketRateChange,
    rothConversionStartYear,
    onRothConversionStartYearChange,
    filingStatus,
    onFilingStatusChange,
    otherIncome,
    onOtherIncomeChange,
}: RothConversionSectionProps) {
    return (
        <>
            <h4 style={{ marginBottom: '0.5rem' }}>Roth Conversion</h4>
            <InfoSection prompt="What is Roth conversion?">
                Moving pre-tax retirement funds (Traditional IRA/401k) to a Roth account. You pay income tax on the converted amount now, but all future growth and withdrawals are tax-free. A conversion ladder spreads conversions over multiple years to stay in lower tax brackets.
            </InfoSection>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem', marginBottom: '1rem' }}>
                <div
                    onClick={() => onRothConversionStrategyChange('fixed_amount')}
                    style={{
                        border: `2px solid ${rothConversionStrategy === 'fixed_amount' ? '#1976d2' : '#e0e0e0'}`,
                        background: rothConversionStrategy === 'fixed_amount' ? '#e3f2fd' : '#fff',
                        cursor: 'pointer',
                        borderRadius: '8px',
                        padding: '1rem',
                    }}
                >
                    <div style={{ fontWeight: 600, marginBottom: '0.5rem' }}>Fixed Amount</div>
                    <div style={{ fontSize: '0.8rem', color: '#666', lineHeight: 1.4 }}>Convert a fixed dollar amount from traditional to Roth each year. Set to $0 to skip conversions.</div>
                </div>
                <div
                    onClick={() => onRothConversionStrategyChange('fill_bracket')}
                    style={{
                        border: `2px solid ${rothConversionStrategy === 'fill_bracket' ? '#1976d2' : '#e0e0e0'}`,
                        background: rothConversionStrategy === 'fill_bracket' ? '#e3f2fd' : '#fff',
                        cursor: 'pointer',
                        borderRadius: '8px',
                        padding: '1rem',
                    }}
                >
                    <div style={{ fontWeight: 600, marginBottom: '0.5rem' }}>Fill Tax Bracket</div>
                    <div style={{ fontSize: '0.8rem', color: '#666', lineHeight: 1.4 }}>Automatically convert enough to fill up to a target tax bracket each year. Optimizes conversions to minimize lifetime taxes.</div>
                </div>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '1rem', marginBottom: '1rem' }}>
                {rothConversionStrategy === 'fixed_amount' && (
                    <div>
                        <label style={labelStyle}>Annual Roth Conversion</label>
                        <input style={inputStyle} type="text" inputMode="decimal" value={formatCurrencyInput(annualRothConversion)} onChange={e => onAnnualRothConversionChange(Number(parseCurrencyInput(e.target.value)) || 0)} />
                        <HelpText>Fixed dollar amount to convert each year. Set to $0 to skip.</HelpText>
                    </div>
                )}
                {rothConversionStrategy === 'fill_bracket' && (
                    <div>
                        <label style={labelStyle}>Target Tax Bracket</label>
                        <select style={inputStyle} value={targetBracketRate} onChange={e => onTargetBracketRateChange(Number(e.target.value))}>
                            <option value={10}>10%</option>
                            <option value={12}>12%</option>
                            <option value={22}>22%</option>
                            <option value={24}>24%</option>
                            <option value={32}>32%</option>
                            <option value={35}>35%</option>
                        </select>
                        <HelpText>Convert enough to fill income up to the top of this bracket each year.</HelpText>
                    </div>
                )}
                {(rothConversionStrategy !== 'fixed_amount' || annualRothConversion > 0) && (
                    <>
                        <div>
                            <label style={labelStyle}>Conversion Start Year</label>
                            <input style={inputStyle} type="number" value={rothConversionStartYear ?? ''} onChange={e => onRothConversionStartYearChange(e.target.value ? Number(e.target.value) : null)} placeholder="e.g., 2035" />
                            <HelpText>Calendar year when Roth conversions begin. Leave blank to start immediately.</HelpText>
                        </div>
                        <div>
                            <label style={labelStyle}>Filing Status</label>
                            <select style={inputStyle} value={filingStatus} onChange={e => onFilingStatusChange(e.target.value)}>
                                <option value="single">Single</option>
                                <option value="married_filing_jointly">Married Filing Jointly</option>
                            </select>
                            <HelpText>Your tax filing status, used to determine the tax bracket for conversion amounts.</HelpText>
                        </div>
                        <div>
                            <label style={labelStyle}>Other Income</label>
                            <input style={inputStyle} type="text" inputMode="decimal" value={formatCurrencyInput(otherIncome)} onChange={e => onOtherIncomeChange(Number(parseCurrencyInput(e.target.value)) || 0)} />
                            <HelpText>Non-retirement income (salary, rental income) that affects which tax bracket your conversions fall into.</HelpText>
                        </div>
                    </>
                )}
            </div>
        </>
    );
}
