package com.tailoredbrands.otd.common.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.UncheckedIOException;

/**
 * The one {@link ObjectMapper} configuration shared by every publisher and consumer
 * (CONVENTIONS "JSON"): JavaTimeModule, ISO-8601 dates, unknown properties ignored,
 * nulls omitted so the Pub/Sub proto-JSON schema validation accepts the payload.
 */
public final class EventJson {

    public static final ObjectMapper MAPPER = newMapper();

    private EventJson() {
    }

    public static ObjectMapper newMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .build();
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("Cannot serialize " + value.getClass().getSimpleName(), e);
        }
    }

    public static byte[] toJsonBytes(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("Cannot serialize " + value.getClass().getSimpleName(), e);
        }
    }

    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("Cannot parse " + type.getSimpleName() + ": " + e.getOriginalMessage(), e);
        }
    }

    public static <T> T fromJson(byte[] json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException("Cannot parse " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }
}
