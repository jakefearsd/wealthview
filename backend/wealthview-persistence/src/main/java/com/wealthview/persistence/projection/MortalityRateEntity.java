package com.wealthview.persistence.projection;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One (sex, age) row of the SSA period-life mortality table used by the stochastic-mortality
 * Monte Carlo sampler. {@code qx} is the probability of death within one year, given alive at
 * exact age -- see migration V080 and {@code R__seed_mortality_rates.sql}.
 *
 * <p>Read-only reference data: no setters, seeded exclusively via Flyway.
 */
@Entity
@Table(name = "mortality_rates")
public class MortalityRateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String sex;

    @Column(nullable = false)
    private int age;

    @Column(nullable = false)
    private BigDecimal qx;

    protected MortalityRateEntity() {
    }

    public UUID getId() {
        return id;
    }

    public String getSex() {
        return sex;
    }

    public int getAge() {
        return age;
    }

    public BigDecimal getQx() {
        return qx;
    }
}
