package com.tailoredbrands.otd.orderintake.legacy;

import com.tailoredbrands.otd.common.event.Address;
import com.tailoredbrands.otd.common.event.Alteration;
import com.tailoredbrands.otd.common.event.Order;
import com.tailoredbrands.otd.common.event.OrderLine;
import com.tailoredbrands.otd.common.event.Rental;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Reverse of the XSLT: writes a canonical {@link Order} as the legacy {@code <Order>} XML that
 * TIBCO BW / the EMS consumers expect (same element names and code tables as legacy-order.xsd).
 * Used by the DUAL_RUN dual-writer. Plain StAX, no JAXB dependency on generated classes.
 */
@Component
public class LegacyOrderXmlWriter {

    public static final String NAMESPACE = "http://tailoredbrands.com/legacy/oms/v1";

    private final XMLOutputFactory factory = XMLOutputFactory.newInstance();

    public String write(Order order) {
        Objects.requireNonNull(order, "order");
        StringWriter out = new StringWriter(1024);
        try {
            XMLStreamWriter w = factory.createXMLStreamWriter(out);
            w.writeStartDocument("UTF-8", "1.0");
            w.writeStartElement("Order");
            w.writeDefaultNamespace(NAMESPACE);
            element(w, "OrderNbr", order.orderId());
            element(w, "OrderType", orderTypeCode(order));
            element(w, "StoreNbr", order.storeId());
            if (order.customerId() != null) {
                element(w, "CustNbr", order.customerId());
            }
            element(w, "OrderDate", order.orderedAt().toString());
            if (order.promisedDate() != null) {
                element(w, "PromiseDate", order.promisedDate().toString());
            }
            element(w, "Currency", order.currency());
            w.writeStartElement("Lines");
            for (OrderLine line : order.lines()) {
                writeLine(w, line);
            }
            w.writeEndElement();
            if (order.rental() != null) {
                writeRental(w, order.rental());
            }
            if (order.shipTo() != null) {
                writeAddress(w, order.shipTo());
            }
            w.writeEndElement();
            w.writeEndDocument();
            w.close();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Cannot write legacy order XML for " + order.orderId(), e);
        }
        return out.toString();
    }

    private static void writeLine(XMLStreamWriter w, OrderLine line) throws XMLStreamException {
        w.writeStartElement("Line");
        element(w, "LineNbr", Integer.toString(line.lineNumber()));
        element(w, "SKU", line.sku());
        element(w, "Qty", Integer.toString(line.quantity()));
        element(w, "Price", plain(line.unitPrice()));
        element(w, "FulfillType", switch (line.fulfillmentType()) {
            case STORE_PICKUP -> "P";
            case SHIP_TO_HOME -> "S";
            case ALTERATION -> "A";
        });
        if (line.alteration() != null) {
            writeAlteration(w, line.alteration());
        }
        w.writeEndElement();
    }

    private static void writeAlteration(XMLStreamWriter w, Alteration alteration) throws XMLStreamException {
        w.writeStartElement("Alteration");
        element(w, "AltType", alteration.type());
        if (alteration.measurementInches() != null) {
            element(w, "Measurement", plain(alteration.measurementInches()));
        }
        if (alteration.tailorShopId() != null) {
            element(w, "TailorShop", alteration.tailorShopId());
        }
        w.writeEndElement();
    }

    private static void writeRental(XMLStreamWriter w, Rental rental) throws XMLStreamException {
        w.writeStartElement("Rental");
        optional(w, "EventNbr", rental.eventId());
        optional(w, "EventDate", rental.eventDate());
        optional(w, "ReturnDate", rental.returnDueDate());
        optional(w, "GroupNbr", rental.groupId());
        w.writeEndElement();
    }

    private static void writeAddress(XMLStreamWriter w, Address address) throws XMLStreamException {
        w.writeStartElement("ShipTo");
        optional(w, "Name", address.name());
        element(w, "Addr1", address.line1());
        optional(w, "Addr2", address.line2());
        element(w, "City", address.city());
        element(w, "State", address.state());
        element(w, "Zip", address.postalCode());
        optional(w, "Country", address.country());
        w.writeEndElement();
    }

    private static String orderTypeCode(Order order) {
        return switch (order.orderType()) {
            case RETAIL -> "R";
            case TAILORED -> "T";
            case CUSTOM -> "C";
            case RENTAL -> "X";
            case ECOM -> "E";
        };
    }

    private static void element(XMLStreamWriter w, String name, String value) throws XMLStreamException {
        w.writeStartElement(name);
        w.writeCharacters(value == null ? "" : value);
        w.writeEndElement();
    }

    private static void optional(XMLStreamWriter w, String name, String value) throws XMLStreamException {
        if (value != null && !value.isBlank()) {
            element(w, name, value);
        }
    }

    private static void optional(XMLStreamWriter w, String name, LocalDate value) throws XMLStreamException {
        if (value != null) {
            element(w, name, value.toString());
        }
    }

    private static String plain(BigDecimal value) {
        return value.toPlainString();
    }
}
