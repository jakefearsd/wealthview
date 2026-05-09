package com.wealthview.core.auth.mfa.dto;

import java.util.List;

public record MfaRecoveryCodesResponse(List<String> recoveryCodes) {
    @Override
    public String toString() {
        return "MfaRecoveryCodesResponse[recoveryCodes=*** ("
                + recoveryCodes.size() + ")]";
    }
}
