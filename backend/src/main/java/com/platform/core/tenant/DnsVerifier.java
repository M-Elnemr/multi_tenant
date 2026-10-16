package com.platform.core.tenant;

import java.util.List;

public interface DnsVerifier {
    /** TXT record values for the given name (empty if none / lookup fails). */
    List<String> txtRecords(String name);

    /** CNAME targets of the given name (empty if none / lookup fails): used to tell the owner that a record has the wrong type. */
    default List<String> cnames(String name) { return List.of(); }

    /** The IP addresses the name finally resolves to, following CNAMEs (empty if it does not resolve). */
    default List<String> addresses(String name) { return List.of(); }
}
