package com.tailoredbrands.otd.orderintake.api;

import com.tailoredbrands.otd.common.event.Rental;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record RentalRequest(
        @Size(max = 64) String eventId,
        LocalDate eventDate,
        LocalDate returnDueDate,
        @Size(max = 64) String groupId) {

    Rental toRental() {
        return new Rental(eventId, eventDate, returnDueDate, groupId);
    }
}
