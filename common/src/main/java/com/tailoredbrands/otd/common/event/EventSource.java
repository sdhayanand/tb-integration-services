package com.tailoredbrands.otd.common.event;

/** {@code source} of an {@link OrderEvent} (ARCHITECTURE §3.1). */
public enum EventSource {
    ORDER_INTAKE_API, LEGACY_SOAP_ADAPTER, TIBCO_EMS_BRIDGE, REPLAY
}
