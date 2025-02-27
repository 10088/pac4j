package org.pac4j.core.credentials.authenticator;

import org.pac4j.core.context.CallContext;
import org.pac4j.core.credentials.Credentials;
import org.pac4j.core.exception.CredentialsException;

import java.util.Optional;

/**
 * An authenticator is responsible for validating {@link Credentials} and should throw a {@link CredentialsException}
 * if the authentication fails.
 *
 * @author Jerome Leleu
 * @since 1.7.0
 */
@FunctionalInterface
public interface Authenticator {

    Authenticator ALWAYS_VALIDATE = new Authenticator() {
        @Override
        public Optional<Credentials> validate(CallContext ctx, Credentials credentials) {
            return Optional.of(credentials);
        }
    };

    /**
     * Validate the credentials. It should throw a {@link CredentialsException} in case of failure.
     *
     * @param ctx the context
     * @param credentials the given credentials
     * @return the credentials
     */
    Optional<Credentials> validate(CallContext ctx, Credentials credentials);
}
