package com.tailoredbrands.otd.shipmentwebhook.carrier;

import com.tailoredbrands.otd.shipmentwebhook.error.UnknownCarrierException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Registry of mappers keyed by carrier code (case-insensitive). */
@Component
public class CarrierPayloadMappers {

    private final Map<String, CarrierPayloadMapper> byCarrier;

    public CarrierPayloadMappers(List<CarrierPayloadMapper> mappers) {
        this.byCarrier = mappers.stream()
                .collect(Collectors.toUnmodifiableMap(m -> m.carrier().toUpperCase(Locale.ROOT), Function.identity()));
    }

    public CarrierPayloadMapper forCarrier(String carrier) {
        CarrierPayloadMapper mapper = carrier == null ? null : byCarrier.get(carrier.toUpperCase(Locale.ROOT));
        if (mapper == null) {
            throw new UnknownCarrierException(carrier);
        }
        return mapper;
    }
}
