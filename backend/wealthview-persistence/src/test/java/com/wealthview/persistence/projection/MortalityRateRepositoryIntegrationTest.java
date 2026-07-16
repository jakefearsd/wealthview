package com.wealthview.persistence.projection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.wealthview.persistence.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

class MortalityRateRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MortalityRateRepository repository;

    @Test
    void findAllBySexOrderByAgeAsc_forFemale_returnsRowsAscendingWithForcedTerminalDeath() {
        List<MortalityRateEntity> rates = repository.findAllBySexOrderByAgeAsc("female");

        assertThat(rates).isNotEmpty();
        assertThat(rates).extracting(MortalityRateEntity::getAge).isSorted();
        assertThat(rates.getFirst().getAge()).isLessThanOrEqualTo(40);
        assertThat(rates.getLast().getQx()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(rates).allSatisfy(rate -> assertThat(rate.getQx())
                .isGreaterThanOrEqualTo(BigDecimal.ZERO)
                .isLessThanOrEqualTo(BigDecimal.ONE));
    }

    @Test
    void findAllBySexOrderByAgeAsc_forMaleAndFemale_bothNonEmptyAndDifferAtAge70() {
        List<MortalityRateEntity> maleRates = repository.findAllBySexOrderByAgeAsc("male");
        List<MortalityRateEntity> femaleRates = repository.findAllBySexOrderByAgeAsc("female");

        assertThat(maleRates).isNotEmpty();
        assertThat(femaleRates).isNotEmpty();

        Map<Integer, BigDecimal> maleByAge = maleRates.stream()
                .collect(Collectors.toMap(MortalityRateEntity::getAge, MortalityRateEntity::getQx));
        Map<Integer, BigDecimal> femaleByAge = femaleRates.stream()
                .collect(Collectors.toMap(MortalityRateEntity::getAge, MortalityRateEntity::getQx));

        assertThat(maleByAge.get(70)).isGreaterThan(femaleByAge.get(70));
    }
}
