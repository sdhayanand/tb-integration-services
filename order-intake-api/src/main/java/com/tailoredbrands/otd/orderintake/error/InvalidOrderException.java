package com.tailoredbrands.otd.orderintake.error;

import java.util.List;

/** Business validation failure (400). Carries one message per violated rule. */
public class InvalidOrderException extends RuntimeException {

    private final List<String> violations;

    public InvalidOrderException(String message) {
        this(List.of(message));
    }

    public InvalidOrderException(List<String> violations) {
        super(String.join("; ", violations));
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
        return violations;
    }
}
