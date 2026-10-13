package com.mmu.schedulingapp.utility;

import java.io.IOException;

/** Only enumerated classifications and fixed messages cross the provider boundary. */
public final class MapsFailure extends IOException {
    public enum Kind { TIMEOUT, HTTP_FAILURE, QUOTA, DENIED, ZERO_RESULTS, MALFORMED_RESPONSE, RESPONSE_TOO_LARGE, UNAVAILABLE }
    private final Kind kind;
    public MapsFailure(Kind kind) { this(kind, "Google Maps request failed."); }
    public MapsFailure(Kind kind, String safeMessage) { super(safeMessage); this.kind = kind; }
    public Kind kind() { return kind; }
}
