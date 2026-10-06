package com.platform.core.tenant;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import org.springframework.stereotype.Component;

@Component
public class JndiDnsVerifier implements DnsVerifier {
    @Override
    public List<String> txtRecords(String name) {
        List<String> out = new ArrayList<>();
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("com.sun.jndi.dns.timeout.initial", "3000");
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            Attributes attrs = new InitialDirContext(env).getAttributes(name, new String[] {"TXT"});
            Attribute txt = attrs.get("TXT");
            if (txt != null) {
                NamingEnumeration<?> e = txt.getAll();
                while (e.hasMore()) out.add(String.valueOf(e.next()).replace("\"", "").trim());
            }
        } catch (Exception ignored) {
            // Missing record / DNS failure: treated as "not verified yet".
        }
        return out;
    }
}
