package com.payverse.common.security;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedUserTest {

    @Test
    void parsesUserIdAndRoles() {
        UUID id = UUID.randomUUID();
        AuthenticatedUser user = new AuthenticatedUser(id.toString(), "d1", Set.of("USER"));
        assertThat(user.userId()).isEqualTo(id);
        assertThat(user.hasRole("USER")).isTrue();
        assertThat(user.isService()).isFalse();
    }

    @Test
    void serviceSubjectsAreNotUserIds() {
        AuthenticatedUser service = new AuthenticatedUser("wallet-service", null, Set.of(PayverseRoles.SERVICE));
        assertThat(service.isService()).isTrue();
        assertThatThrownBy(service::userId).isInstanceOf(IllegalStateException.class);
    }
}
