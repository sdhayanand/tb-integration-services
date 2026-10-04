package com.tailoredbrands.otd.common.event;

import java.time.LocalDate;

/**
 * Rental details for {@link OrderType#RENTAL} orders (tuxedo rental for an event).
 *
 * @param eventId       the wedding / prom / event id that links a rental party
 * @param eventDate     date of the event
 * @param returnDueDate date the garments are due back
 * @param groupId       rental party group id (all members of the party share it)
 */
public record Rental(
        String eventId,
        LocalDate eventDate,
        LocalDate returnDueDate,
        String groupId) {
}
