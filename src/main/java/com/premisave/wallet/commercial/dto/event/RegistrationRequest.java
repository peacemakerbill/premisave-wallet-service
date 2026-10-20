package com.premisave.wallet.commercial.dto.event;

import com.premisave.wallet.commercial.enums.RegistrationType;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Create uses every field; update takes the same shape with all fields optional. */
public record RegistrationRequest(
        RegistrationType type,
        @Size(max = 2048) String url,
        List<@Size(max = 60) String> eventTypes,
        @Size(max = 10) String onTimeout,
        @Size(max = 200) String description) {
}
