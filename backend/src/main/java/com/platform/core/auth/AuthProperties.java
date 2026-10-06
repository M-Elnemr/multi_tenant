package com.platform.core.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.auth")
public record AuthProperties(Duration activationPinTtl, int maxActivationAttempts) {}
