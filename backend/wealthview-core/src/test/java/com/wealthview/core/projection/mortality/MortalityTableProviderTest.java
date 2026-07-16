package com.wealthview.core.projection.mortality;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wealthview.persistence.projection.MortalityRateEntity;
import com.wealthview.persistence.projection.MortalityRateRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MortalityTableProviderTest {

    private static MortalityRateEntity rate(int age, String qx) {
        var entity = mock(MortalityRateEntity.class);
        when(entity.getAge()).thenReturn(age);
        when(entity.getQx()).thenReturn(new BigDecimal(qx));
        return entity;
    }

    private static MortalityRateRepository repositoryWithSeedRows() {
        var maleRates = List.of(rate(70, "0.02"), rate(120, "1.0"));
        var femaleRates = List.of(rate(70, "0.012"), rate(120, "1.0"));

        var repository = mock(MortalityRateRepository.class);
        when(repository.findAllBySexOrderByAgeAsc("male")).thenReturn(maleRates);
        when(repository.findAllBySexOrderByAgeAsc("female")).thenReturn(femaleRates);
        return repository;
    }

    @Test
    void load_buildsTableFromRepository() {
        var repository = repositoryWithSeedRows();
        var provider = new MortalityTableProvider(repository);

        var table = provider.load();

        assertThat(table.qx("male", 70)).isEqualTo(0.02);
        assertThat(table.qx("female", 70)).isEqualTo(0.012);
    }

    @Test
    void load_calledTwice_secondCallDoesNotHitRepository() {
        var repository = repositoryWithSeedRows();
        var provider = new MortalityTableProvider(repository);

        provider.load();
        provider.load();

        verify(repository, times(1)).findAllBySexOrderByAgeAsc("male");
        verify(repository, times(1)).findAllBySexOrderByAgeAsc("female");
    }

    @Test
    void load_femaleRowsEmpty_throwsNamingMissingSex() {
        var maleRates = List.of(rate(70, "0.02"));

        var repository = mock(MortalityRateRepository.class);
        when(repository.findAllBySexOrderByAgeAsc("male")).thenReturn(maleRates);
        when(repository.findAllBySexOrderByAgeAsc("female")).thenReturn(List.of());
        var provider = new MortalityTableProvider(repository);

        assertThatThrownBy(provider::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("female");
    }

    @Test
    void clearCache_afterLoad_forcesNextLoadToHitRepositoryAgain() {
        var repository = repositoryWithSeedRows();
        var provider = new MortalityTableProvider(repository);
        provider.load();

        provider.clearCache();
        provider.load();

        verify(repository, times(2)).findAllBySexOrderByAgeAsc("male");
        verify(repository, times(2)).findAllBySexOrderByAgeAsc("female");
    }
}
