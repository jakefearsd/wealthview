package com.wealthview.core.property.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SelectZpidRequest(
        // A Zillow ZPID is a numeric identifier that is concatenated into an
        // outbound Zillow URL path; restrict it to digits so no path/query or
        // host manipulation can be attempted through this field.
        @NotBlank @Pattern(regexp = "\\d{1,15}", message = "zpid must be 1-15 digits") String zpid
) {
}
