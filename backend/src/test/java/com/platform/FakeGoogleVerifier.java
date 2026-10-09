package com.platform;

import com.platform.core.auth.GoogleIdTokenVerifier;
import com.platform.shared.BusinessException;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Stands in for Google in tests: the credential is "test|<sub>|<email>|<name>"; anything else is rejected like a bad Google token. */
@Component
@Primary
public class FakeGoogleVerifier implements GoogleIdTokenVerifier {
    @Override public String clientId() { return "test-client-id"; }

    @Override
    public GoogleIdentity verify(String idToken) {
        String[] p = idToken == null ? new String[0] : idToken.split("\\|");
        if (p.length != 4 || !"test".equals(p[0])) throw BusinessException.unauthorized("GOOGLE_TOKEN_INVALID", "Google sign-in failed, please try again");
        return new GoogleIdentity(p[1], p[2], p[3]);
    }
}
