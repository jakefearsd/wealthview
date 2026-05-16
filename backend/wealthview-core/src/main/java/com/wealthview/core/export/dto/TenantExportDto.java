package com.wealthview.core.export.dto;

import java.util.List;

import com.wealthview.core.account.dto.AccountResponse;
import com.wealthview.core.holding.dto.HoldingResponse;
import com.wealthview.core.property.dto.PropertyResponse;
import com.wealthview.core.transaction.dto.TransactionResponse;

public record TenantExportDto(
        List<AccountResponse> accounts,
        List<TransactionResponse> transactions,
        List<HoldingResponse> holdings,
        List<PropertyResponse> properties
) {}
