package com.tailoredbrands.otd.orderintake.soap;

import com.fasterxml.jackson.databind.JsonNode;
import com.tailoredbrands.otd.common.event.Order;
import com.tailoredbrands.otd.common.json.EventJson;
import com.tailoredbrands.otd.orderintake.error.InvalidOrderException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.xml.XMLConstants;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs {@code xslt/legacy-order-to-canonical.xsl} (XSLT 1.0 on the JDK's built-in processor) to turn
 * legacy OMS XML into canonical {@link Order} JSON. The compiled {@link Templates} is thread-safe;
 * a {@link Transformer} is created per call.
 */
@Component
public class LegacyOrderTransformer {

    public static final String STYLESHEET = "xslt/legacy-order-to-canonical.xsl";
    /** JSON array the stylesheet adds when it meets unknown legacy codes. */
    public static final String ERRORS_FIELD = "_errors";

    private final Templates templates;

    public LegacyOrderTransformer() {
        try (InputStream in = new ClassPathResource(STYLESHEET).getInputStream()) {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            this.templates = factory.newTemplates(new StreamSource(in));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + STYLESHEET, e);
        } catch (TransformerConfigurationException e) {
            throw new IllegalStateException("Cannot compile " + STYLESHEET + ": " + e.getMessage(), e);
        }
    }

    /** @return canonical Order JSON text (no orderId) */
    public String toCanonicalJson(String legacyXml) {
        if (legacyXml == null || legacyXml.isBlank()) {
            throw new InvalidOrderException("legacy XML is empty");
        }
        try {
            Transformer transformer = templates.newTransformer();
            StringWriter out = new StringWriter(1024);
            transformer.transform(new StreamSource(new StringReader(legacyXml)), new StreamResult(out));
            return out.toString();
        } catch (TransformerException e) {
            throw new InvalidOrderException("legacy XML cannot be transformed: " + rootMessage(e));
        }
    }

    /**
     * @throws InvalidOrderException when the XML is not well-formed, contains unknown legacy codes
     *                               (reported by the stylesheet in {@code _errors}) or does not map to an Order
     */
    public Order toCanonicalOrder(String legacyXml) {
        String json = toCanonicalJson(legacyXml);
        try {
            JsonNode tree = EventJson.MAPPER.readTree(json);
            JsonNode errors = tree.get(ERRORS_FIELD);
            if (errors != null && errors.isArray() && !errors.isEmpty()) {
                List<String> messages = new ArrayList<>();
                errors.forEach(node -> messages.add(node.asText()));
                throw new InvalidOrderException(messages);
            }
            return EventJson.MAPPER.treeToValue(tree, Order.class);
        } catch (IOException e) {
            throw new InvalidOrderException("transformed legacy order is not a valid canonical order: " + e.getMessage());
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }
}
