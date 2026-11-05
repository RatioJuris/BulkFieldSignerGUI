// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.net.URI;

/** RFC 3161 authorities. Plain-HTTP endpoints are acceptable because every token's signature and path are verified. */
public enum TimestampProvider {
    NONE("No timestamp", null),
    DIGICERT("DigiCert", URI.create("http://timestamp.digicert.com")), // DigiCert does not serve RFC 3161 over HTTPS.
    SECTIGO("Sectigo", URI.create("https://timestamp.sectigo.com")),
    GLOBALSIGN("GlobalSign", URI.create("http://timestamp.globalsign.com/tsa/r6advanced1")),
    SSL_COM("SSL.com", URI.create("http://ts.ssl.com")),
    ENTRUST("Entrust", URI.create("http://timestamp.entrust.net/TSS/RFC3161sha2TS"));

    private final String label;
    private final URI uri;
    TimestampProvider(String label, URI uri) { this.label = label; this.uri = uri; }
    public URI uri() { return uri; }
    public String label() { return label; }
    @Override public String toString() { return this == NONE ? label : label + " (RFC 3161)"; }

    public static TimestampProvider parse(String value, TimestampProvider fallback) {
        try { return value == null ? fallback : valueOf(value); }
        catch (IllegalArgumentException ex) { return fallback; }
    }
}
