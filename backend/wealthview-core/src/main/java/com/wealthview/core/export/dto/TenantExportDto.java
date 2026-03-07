package com.wealthview.core.export.dto;

import com.wealthview.core.account.dto.AccountResponse;
import com.wealthview.core.holding.dto.HoldingResponse;
import com.wealthview.core.property.dto.PropertyResponse;
import com.wealthview.core.transaction.dto.TransactionResponse;

import java.util.List;

public record TenantExportDto(
        List<AccountResponse> accounts,
        List<TransactionResponse> transactions,
        List<HoldingResponse> holdings,
        List<PropertyResponse> properties
) {}
