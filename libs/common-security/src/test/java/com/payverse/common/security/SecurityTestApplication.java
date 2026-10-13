package com.payverse.common.security;

import com.payverse.common.test.TestJwts;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
class SecurityTestApplication {

    @Bean
    JwtDecoder jwtDecoder() {
        return PayverseJwtValidators.configure((NimbusJwtDecoder) TestJwts.INSTANCE.jwtDecoder(), TestJwts.ISSUER, "payverse");
    }

    @Bean
    PayverseHttpSecurityCustomizer otpIsPublic() {
        return auth -> auth.requestMatchers("/api/v1/auth/otp/**").permitAll();
    }

    @RestController
    static class TestController {

        @GetMapping("/api/v1/me")
        AuthenticatedUser me(AuthenticatedUser user) {
            return user;
        }

        @GetMapping("/api/v1/public/ping")
        String publicPing() {
            return "pong";
        }

        @GetMapping("/api/v1/auth/otp/ping")
        String otpPing() {
            return "pong";
        }

        @GetMapping("/internal/ping")
        String internalPing() {
            return "pong";
        }

        @GetMapping("/api/v1/admin/ping")
        String adminPing() {
            return "pong";
        }

        @PreAuthorize("hasRole('ADMIN')")
        @GetMapping("/api/v1/reports")
        String adminOnlyByAnnotation() {
            return "report";
        }

        @GetMapping("/api/v1/current")
        String current() {
            return CurrentUser.require().subject() + "|" + CurrentUser.bearerToken().isPresent();
        }
    }
}
