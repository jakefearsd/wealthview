package com.wealthview.core.portfolio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PortfolioDataPointDto(LocalDate date, BigDecimal totalValue) {}
