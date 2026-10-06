package com.platform.core.tenant;

import java.util.List;

public interface DnsVerifier {
    /** TXT record values for the given name (empty if none / lookup fails). */
    List<String> txtRecords(String name);
}
