package com.tailoredbrands.otd.orderintake.api;

import com.tailoredbrands.otd.common.event.Alteration;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record AlterationRequest(
        @NotBlank @Size(max = 32) String type,
        @DecimalMin("0.0") @DecimalMax("120.0") BigDecimal measurementInches,
        @Size(max = 32) String tailorShopId) {

    Alteration toAlteration() {
        return new Alteration(type, measurementInches, tailorShopId);
    }
}
