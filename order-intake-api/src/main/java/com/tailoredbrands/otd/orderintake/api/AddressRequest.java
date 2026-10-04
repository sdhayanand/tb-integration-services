package com.tailoredbrands.otd.orderintake.api;

import com.tailoredbrands.otd.common.event.Address;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddressRequest(
        @Size(max = 128) String name,
        @NotBlank @Size(max = 128) String line1,
        @Size(max = 128) String line2,
        @NotBlank @Size(max = 64) String city,
        @NotBlank @Size(max = 32) String state,
        @NotBlank @Size(max = 16) String postalCode,
        @Size(min = 2, max = 2) String country) {

    Address toAddress() {
        return new Address(name, line1, line2, city, state, postalCode, country == null ? "US" : country);
    }
}
