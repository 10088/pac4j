package org.pac4j.saml.context;

import org.pac4j.core.context.CallContext;
import org.pac4j.saml.client.SAML2Client;

/**
 * Builds the saml context for SP and the IDP.
 * @author Misagh Moayyed
 * @since 1.7
 */
public interface SAMLContextProvider {
    SAML2MessageContext buildServiceProviderContext(CallContext ctx, SAML2Client client);

    SAML2MessageContext buildContext(CallContext ctx, SAML2Client client);
}
