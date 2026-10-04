package com.tailoredbrands.otd.common.event;

import java.math.BigDecimal;

/**
 * Alteration work attached to an order line with {@link FulfillmentType#ALTERATION}.
 *
 * @param type              e.g. {@code HEM}, {@code SLEEVE}, {@code WAIST}, {@code TAPER}
 * @param measurementInches measurement for the tailor, in inches
 * @param tailorShopId      regional tailor shop or in-store tailor id (e.g. {@code TS-EASTBAY})
 */
public record Alteration(
        String type,
        BigDecimal measurementInches,
        String tailorShopId) {
}
