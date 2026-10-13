package com.payverse.auth.application.port;

import java.util.Map;

/** Publishes the JWKS (public signing keys) as a JSON object. */
public interface PublicKeyProvider {

    Map<String, Object> publicJwks();
}
