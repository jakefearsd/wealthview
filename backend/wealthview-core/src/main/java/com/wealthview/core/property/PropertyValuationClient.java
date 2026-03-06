package com.wealthview.core.property;

import com.wealthview.core.property.dto.PropertyValuationResult;

import java.util.Optional;

public interface PropertyValuationClient {

    Optional<PropertyValuationResult> getValuation(String address);
}
