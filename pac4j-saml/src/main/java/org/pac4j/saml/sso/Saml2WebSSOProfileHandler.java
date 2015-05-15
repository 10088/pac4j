/*
  Copyright 2012 -2014 Michael Remond

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package org.pac4j.saml.sso;

import org.opensaml.messaging.context.MessageContext;
import org.opensaml.messaging.decoder.MessageDecoder;
import org.opensaml.messaging.decoder.MessageDecodingException;
import org.opensaml.messaging.encoder.MessageEncoder;
import org.opensaml.messaging.encoder.MessageEncodingException;
import org.opensaml.saml.common.SAMLObject;
import org.opensaml.saml.saml2.core.AuthnRequest;
import org.opensaml.saml.saml2.metadata.IDPSSODescriptor;
import org.opensaml.saml.saml2.metadata.SPSSODescriptor;
import org.opensaml.saml.saml2.metadata.SingleSignOnService;
import org.opensaml.xml.parse.StaticBasicParserPool;
import org.opensaml.xml.security.SecurityException;
import org.opensaml.xml.signature.SignatureTrustEngine;
import org.pac4j.saml.crypto.CredentialProvider;
import org.pac4j.saml.exceptions.SamlException;
import org.pac4j.saml.util.SamlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler capable of sending and receiving SAML messages according to the SAML2 SSO Browser profile.
 * 
 * @author Michael Remond
 * @since 1.5.0
 */
@SuppressWarnings("rawtypes")
public class Saml2WebSSOProfileHandler {

    private final static Logger logger = LoggerFactory.getLogger(Saml2WebSSOProfileHandler.class);

    private final CredentialProvider credentialProvider;

    private final MessageEncoder encoder;

    private final MessageDecoder decoder;

    private final StaticBasicParserPool parserPool;
    
    private String destinationBindingType;

    // SAML2 SSO browser profile because not available in opensaml constants
    public static final String SAML2_WEBSSO_PROFILE_URI = "urn:oasis:names:tc:SAML:2.0:profiles:SSO:browser";

    public Saml2WebSSOProfileHandler(final CredentialProvider credentialProvider, final MessageEncoder encoder,
            final MessageDecoder decoder, final StaticBasicParserPool parserPool, String destinationBindingType) {
        this.credentialProvider = credentialProvider;
        this.encoder = encoder;
        this.decoder = decoder;
        this.parserPool = parserPool;
        this.destinationBindingType = destinationBindingType;
    }

    @SuppressWarnings("unchecked")
    public void sendMessage(final MessageContext<SAMLObject> context, final AuthnRequest authnRequest, final String relayState) {

        SPSSODescriptor spDescriptor = (SPSSODescriptor) context.getLocalEntityRoleMetadata();
        IDPSSODescriptor idpssoDescriptor = (IDPSSODescriptor) context.getPeerEntityRoleMetadata();
        SingleSignOnService ssoService = SamlUtils.getSingleSignOnService(idpssoDescriptor, destinationBindingType);

        context.setCommunicationProfileId(SAML2_WEBSSO_PROFILE_URI);
        context.setOutboundMessage(authnRequest);
        context.setOutboundSAMLMessage(authnRequest);
        context.setPeerEntityEndpoint(ssoService);

        if (relayState != null) {
            context.setRelayState(relayState);
        }

        if (spDescriptor.isAuthnRequestsSigned()) {
            context.setOutboundSAMLMessageSigningCredential(credentialProvider.getCredential());
        } else if (idpssoDescriptor.getWantAuthnRequestsSigned()) {
            logger.warn("IdP wants authn requests signed, it will perhaps reject your authn requests unless you provide a keystore");
        }

        try {
            encoder.setMessageContext(context);
            encoder.encode();
        } catch (MessageEncodingException e) {
            throw new SamlException("Error encoding saml message", e);
        }

    }

    public void receiveMessage(final SAMLMessageContext context, final SignatureTrustEngine engine) {

        context.setPeerEntityRole(IDPSSODescriptor.DEFAULT_ELEMENT_NAME);
        context.setInboundSAMLProtocol(SAMLConstants.SAML20P_NS);

        SecurityPolicy policy = new BasicSecurityPolicy();
        policy.getPolicyRules().add(new SAML2HTTPPostSimpleSignRule(engine, parserPool, engine.getKeyInfoResolver()));
        policy.getPolicyRules().add(new SAMLProtocolMessageXMLSignatureSecurityPolicyRule(engine));
        StaticSecurityPolicyResolver resolver = new StaticSecurityPolicyResolver(policy);
        context.setSecurityPolicyResolver(resolver);

        try {
            decoder.decode(context);
        } catch (MessageDecodingException e) {
            throw new SamlException("Error decoding saml message", e);
        } catch (SecurityException e) {
            throw new SamlException("Error decoding saml message", e);
        }

        if (context.getPeerEntityMetadata() == null) {
            throw new SamlException("IDP Metadata cannot be null");
        }

        context.setPeerEntityId(context.getPeerEntityMetadata().getEntityID());
        context.setCommunicationProfileId(SAML2_WEBSSO_PROFILE_URI);
    }

}
