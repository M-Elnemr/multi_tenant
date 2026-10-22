package com.platform.integrations;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The platform-level app keys elmanassa registered with Salla, YouCan and Amazon (env). Owners log in through these; without them the "log in" button is hidden and token fields remain. */
@Component
public class OAuthApps {
    public record App(String clientId, String clientSecret, String extra) {
        public boolean ok() { return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank(); }
    }

    private final App salla, youcan, amazon;
    private final String rootDomain;

    public OAuthApps(@Value("${app.integrations.salla.client-id:}") String sallaId, @Value("${app.integrations.salla.client-secret:}") String sallaSecret,
                     @Value("${app.integrations.youcan.client-id:}") String youcanId, @Value("${app.integrations.youcan.client-secret:}") String youcanSecret,
                     @Value("${app.integrations.amazon.client-id:}") String amazonId, @Value("${app.integrations.amazon.client-secret:}") String amazonSecret,
                     @Value("${app.integrations.amazon.application-id:}") String amazonAppId, @Value("${app.platform.root-domain}") String rootDomain) {
        this.salla = new App(sallaId, sallaSecret, null);
        this.youcan = new App(youcanId, youcanSecret, null);
        this.amazon = new App(amazonId, amazonSecret, amazonAppId);
        this.rootDomain = rootDomain;
    }

    public App of(String provider) {
        return switch (provider) { case "SALLA" -> salla; case "YOUCAN" -> youcan; case "AMAZON" -> amazon; default -> new App(null, null, null); };
    }

    /** Fixed return address on the platform host (the platforms need a registered redirect), like the Meta login. */
    public String redirectUri(String provider) { return "https://" + rootDomain + "/api/v1/integrations/oauth/" + provider.toLowerCase() + "/callback"; }
}
