package com.tailoredbrands.otd.orderintake.api;

import com.tailoredbrands.otd.common.event.FulfillmentType;
import com.tailoredbrands.otd.common.event.OrderLine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateOrderLineRequest(
        @Positive Integer lineNumber,
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Z0-9][A-Z0-9\\-]*",
                message = "must be an upper-case SKU like MW-SUIT-NAVY-42R") String sku,
        @NotNull @Min(1) @Max(999) Integer quantity,
        @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal unitPrice,
        @NotNull FulfillmentType fulfillmentType,
        @Valid AlterationRequest alteration) {

    OrderLine toOrderLine(int defaultLineNumber) {
        return new OrderLine(
                lineNumber == null ? defaultLineNumber : lineNumber,
                sku,
                quantity,
                unitPrice,
                fulfillmentType,
                alteration == null ? null : alteration.toAlteration());
    }
}
