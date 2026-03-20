package com.wealthview.core.projection.tax;

import com.wealthview.persistence.repository.StateStandardDeductionRepository;
import com.wealthview.persistence.repository.StateTaxBracketRepository;
import com.wealthview.persistence.repository.StateTaxSurchargeRepository;

import java.math.BigDecimal;

public class CaliforniaStateTaxCalculator implements StateTaxCalculator {

    private final BracketBasedStateTaxCalculator delegate;

    public CaliforniaStateTaxCalculator(StateTaxBracketRepository bracketRepository,
                                         StateStandardDeductionRepository deductionRepository,
                                         StateTaxSurchargeRepository surchargeRepository) {
        this.delegate = new BracketBasedStateTaxCalculator(
                "CA", true, bracketRepository, deductionRepository, surchargeRepository);
    }

    @Override
    public BigDecimal computeTax(BigDecimal grossIncome, int taxYear, FilingStatus status) {
        return delegate.computeTax(grossIncome, taxYear, status);
    }

    @Override
    public BigDecimal getStandardDeduction(int taxYear, FilingStatus status) {
        return delegate.getStandardDeduction(taxYear, status);
    }

    @Override
    public String stateCode() {
        return delegate.stateCode();
    }

    @Override
    public boolean taxesCapitalGainsAsOrdinaryIncome() {
        return delegate.taxesCapitalGainsAsOrdinaryIncome();
    }
}
