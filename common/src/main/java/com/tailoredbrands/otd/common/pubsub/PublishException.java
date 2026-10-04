package com.tailoredbrands.otd.common.pubsub;

/** Raised when a Pub/Sub publish fails or times out. */
public class PublishException extends RuntimeException {

    public PublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
