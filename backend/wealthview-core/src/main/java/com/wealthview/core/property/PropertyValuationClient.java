package com.wealthview.core.property;

import java.util.List;
import java.util.Optional;

import com.wealthview.core.property.dto.PropertyValuationResult;
import com.wealthview.core.property.dto.ZillowSearchResult;

public interface PropertyValuationClient {

    Optional<PropertyValuationResult> getValuation(String address);

    Optional<PropertyValuationResult> getValuationByZpid(String zpid);

    List<ZillowSearchResult> searchProperties(String address);
}
