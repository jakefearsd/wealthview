import React from 'react';
import { formatCurrency } from '../utils/format';
import { tableStyle } from '../utils/styles';
import TaxBreakdownChart from './TaxBreakdownChart';
import type { ProjectionYear } from '../types/projection';

interface IncomeTaxTabProps {
    yearlyData: ProjectionYear[];
    retirementYear: number | null;
    expandedTaxYears: Set<number>;
    onToggleTaxYear: (year: number) => void;
}

export default function IncomeTaxTab({
    yearlyData,
    retirementYear,
    expandedTaxYears,
    onToggleTaxYear,
}: IncomeTaxTabProps) {
    const hasStateTax = yearlyData.some(y => y.state_tax != null);
    const stickyTh: React.CSSProperties = {
        textAlign: 'right',
        padding: '0.5rem',
        position: 'sticky',
        top: 0,
        background: '#fff',
    };
    const detailColSpan = hasStateTax ? 7 : 3;

    return (
        <div>
            <h4 style={{ marginBottom: '0.5rem' }}>
                {hasStateTax ? 'Tax Breakdown: Federal + State' : 'Tax Burden Over Time'}
            </h4>
            <TaxBreakdownChart
                data={yearlyData}
                retirementYear={retirementYear}
                hasStateTax={hasStateTax}
            />
            <h4 style={{ marginTop: '1.5rem', marginBottom: '0.5rem' }}>Year-by-Year Detail</h4>
            <div style={{ maxHeight: '70vh', overflow: 'auto' }}>
                <table style={tableStyle}>
                    <thead>
                        <tr style={{ borderBottom: '2px solid #e0e0e0' }}>
                            <th style={{ ...stickyTh, textAlign: 'left' }}>Year</th>
                            <th style={stickyTh}>Age</th>
                            <th style={stickyTh}>Rental Gross</th>
                            <th style={stickyTh}>Rental Exp.</th>
                            <th style={stickyTh}>Depreciation</th>
                            <th style={stickyTh}>Loss Applied</th>
                            <th style={stickyTh}>Suspended Loss</th>
                            <th style={stickyTh}>SS Taxable</th>
                            <th style={stickyTh}>SE Tax</th>
                            {hasStateTax && <th style={stickyTh}>Federal Tax</th>}
                            {hasStateTax && <th style={stickyTh}>State Tax</th>}
                            {hasStateTax && <th style={stickyTh}>SALT</th>}
                            {hasStateTax && <th style={stickyTh}>Deduction</th>}
                            <th style={stickyTh}>Tax Liability</th>
                        </tr>
                    </thead>
                    <tbody>
                        {yearlyData.filter(y => y.retired).map(y => {
                            const hasDetails = y.rental_property_details && y.rental_property_details.length > 0;
                            const isExpanded = expandedTaxYears.has(y.year);
                            return (
                                <React.Fragment key={y.year}>
                                    <tr
                                        style={{
                                            borderBottom: '1px solid #f0f0f0',
                                            background: '#fff8e1',
                                            cursor: hasDetails ? 'pointer' : 'default',
                                        }}
                                        onClick={() => hasDetails && onToggleTaxYear(y.year)}
                                    >
                                        <td style={{ padding: '0.5rem' }}>
                                            {hasDetails ? (isExpanded ? '\u25BC ' : '\u25B6 ') : '  '}{y.year}
                                        </td>
                                        <td style={{ padding: '0.5rem', textAlign: 'right' }}>{y.age}</td>
                                        <td style={{ padding: '0.5rem', textAlign: 'right', color: '#2e7d32' }}>
                                            {y.rental_income_gross != null ? formatCurrency(y.rental_income_gross) : '-'}
                                        </td>
                                        <td style={{ padding: '0.5rem', textAlign: 'right', color: '#d32f2f' }}>
                                            {y.rental_expenses_total != null ? formatCurrency(y.rental_expenses_total) : '-'}
                                        </td>
                                        <td style={{ padding: '0.5rem', textAlign: 'right', color: '#6a1b9a' }}>
                                            {y.depreciation_total != null ? formatCurrency(y.depreciation_total) : '-'}
                                        </td>
                                        <td style={{ padding: '0.5rem', textAlign: 'right', color: '#2e7d32' }}>
                                            {y.rental_loss_applied != null && y.rental_loss_applied > 0 ? formatCurrency(y.rental_loss_applied) : '-'}
                                        </td>
                                        <td style={{ padding: '0.5rem', textAlign: 'right', color: '#e65100' }}>
                                            {y.suspended_loss_carryforward != null && y.suspended_loss_carryforward > 0 ? formatCurrency(y.suspended_loss_carryforward) : '-'}
                                        </td>
                                        <td style={{ padding: '0.5rem', textAlign: 'right' }}>
                                            {y.social_security_taxable != null ? formatCurrency(y.social_security_taxable) : '-'}
                                        </td>
                                        <td style={{ padding: '0.5rem', textAlign: 'right', color: '#d32f2f' }}>
                                            {y.self_employment_tax != null && y.self_employment_tax > 0 ? formatCurrency(y.self_employment_tax) : '-'}
                                        </td>
                                        {hasStateTax && (
                                            <td style={{ padding: '0.5rem', textAlign: 'right', color: '#d32f2f' }}>
                                                {y.federal_tax != null ? formatCurrency(y.federal_tax) : '-'}
                                            </td>
                                        )}
                                        {hasStateTax && (
                                            <td style={{ padding: '0.5rem', textAlign: 'right', color: '#e65100' }}>
                                                {y.state_tax != null ? formatCurrency(y.state_tax) : '-'}
                                            </td>
                                        )}
                                        {hasStateTax && (
                                            <td style={{ padding: '0.5rem', textAlign: 'right' }}>
                                                {y.salt_deduction != null ? formatCurrency(y.salt_deduction) : '-'}
                                            </td>
                                        )}
                                        {hasStateTax && (
                                            <td style={{ padding: '0.5rem', textAlign: 'right' }}>
                                                {y.used_itemized_deduction != null ? (
                                                    <span style={{
                                                        fontSize: '0.75rem',
                                                        padding: '1px 6px',
                                                        borderRadius: 3,
                                                        background: y.used_itemized_deduction ? '#bbdefb' : '#e0e0e0',
                                                        color: '#333',
                                                    }}>
                                                        {y.used_itemized_deduction ? 'Itemized' : 'Standard'}
                                                    </span>
                                                ) : '-'}
                                            </td>
                                        )}
                                        <td style={{ padding: '0.5rem', textAlign: 'right', color: '#d32f2f', fontWeight: 600 }}>
                                            {y.tax_liability != null ? formatCurrency(y.tax_liability) : '-'}
                                        </td>
                                    </tr>
                                    {isExpanded && y.rental_property_details?.map(d => (
                                        <tr key={d.income_source_id} style={{ background: '#f5f5f5', fontSize: '0.85rem' }}>
                                            <td style={{ padding: '0.3rem 0.75rem', paddingLeft: '2rem' }} colSpan={2}>
                                                {d.property_name}
                                                {' '}
                                                <span style={{
                                                    fontSize: '0.7rem',
                                                    padding: '1px 5px',
                                                    borderRadius: 3,
                                                    background: d.tax_treatment === 'rental_passive' ? '#e0e0e0'
                                                        : d.tax_treatment === 'rental_active_reps' ? '#c8e6c9' : '#bbdefb',
                                                    color: '#333',
                                                }}>
                                                    {d.tax_treatment === 'rental_passive' ? 'Passive'
                                                        : d.tax_treatment === 'rental_active_reps' ? 'REPS' : 'STR'}
                                                </span>
                                            </td>
                                            <td style={{ padding: '0.3rem 0.75rem', textAlign: 'right', color: '#2e7d32' }}>
                                                {formatCurrency(d.gross_rent)}
                                            </td>
                                            <td style={{ padding: '0.3rem 0.75rem', textAlign: 'right', color: '#d32f2f' }}>
                                                {formatCurrency(d.operating_expenses + d.mortgage_interest + d.property_tax)}
                                            </td>
                                            <td style={{ padding: '0.3rem 0.75rem', textAlign: 'right', color: '#6a1b9a' }}>
                                                {formatCurrency(d.depreciation)}
                                            </td>
                                            <td style={{ padding: '0.3rem 0.75rem', textAlign: 'right', color: '#2e7d32' }}>
                                                {d.loss_applied_to_income > 0 ? formatCurrency(d.loss_applied_to_income) : '-'}
                                            </td>
                                            <td style={{ padding: '0.3rem 0.75rem', textAlign: 'right', color: '#e65100' }}>
                                                {d.suspended_loss_carryforward > 0 ? formatCurrency(d.suspended_loss_carryforward) : '-'}
                                            </td>
                                            <td colSpan={detailColSpan}></td>
                                        </tr>
                                    ))}
                                </React.Fragment>
                            );
                        })}
                    </tbody>
                </table>
            </div>
        </div>
    );
}
