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
package org.pac4j.saml.sso.impl;

import net.shibboleth.utilities.java.support.resolver.CriteriaSet;
import org.joda.time.DateTime;
import org.opensaml.core.criterion.EntityIdCriterion;
import org.pac4j.core.credentials.Credentials;
import org.opensaml.messaging.context.MessageContext;
import org.opensaml.saml.common.SAMLObject;
import org.opensaml.saml.common.messaging.context.SAMLPeerEntityContext;
import org.opensaml.saml.common.messaging.context.SAMLSelfEntityContext;
import org.opensaml.saml.common.messaging.context.SAMLSubjectNameIdentifierContext;
import org.opensaml.saml.common.xml.SAMLConstants;
import org.opensaml.saml.criterion.EntityRoleCriterion;
import org.opensaml.saml.criterion.ProtocolCriterion;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.Attribute;
import org.opensaml.saml.saml2.core.AttributeStatement;
import org.opensaml.saml.saml2.core.Audience;
import org.opensaml.saml.saml2.core.AudienceRestriction;
import org.opensaml.saml.saml2.core.AuthnStatement;
import org.opensaml.saml.saml2.core.BaseID;
import org.opensaml.saml.saml2.core.Conditions;
import org.opensaml.saml.saml2.core.EncryptedAssertion;
import org.opensaml.saml.saml2.core.EncryptedAttribute;
import org.opensaml.saml.saml2.core.EncryptedID;
import org.opensaml.saml.saml2.core.Issuer;
import org.opensaml.saml.saml2.core.NameID;
import org.opensaml.saml.saml2.core.NameIDType;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.saml.saml2.core.StatusCode;
import org.opensaml.saml.saml2.core.Subject;
import org.opensaml.saml.saml2.core.SubjectConfirmation;
import org.opensaml.saml.saml2.core.SubjectConfirmationData;
import org.opensaml.saml.saml2.encryption.Decrypter;
import org.opensaml.saml.saml2.metadata.IDPSSODescriptor;
import org.opensaml.saml.saml2.metadata.SPSSODescriptor;
import org.opensaml.saml.security.impl.SAMLSignatureProfileValidator;
import org.opensaml.security.SecurityException;
import org.opensaml.security.credential.UsageType;
import org.opensaml.security.criteria.UsageCriterion;
import org.opensaml.xmlsec.encryption.support.DecryptionException;
import org.opensaml.xmlsec.signature.Signature;
import org.opensaml.xmlsec.signature.support.SignatureException;
import org.opensaml.xmlsec.signature.support.SignatureTrustEngine;
import org.pac4j.saml.client.SAML2Client;
import org.pac4j.saml.context.ExtendedSAMLMessageContext;
import org.pac4j.saml.credentials.Saml2Credentials;
import org.pac4j.saml.crypto.SAML2SignatureTrustEngineProvider;
import org.pac4j.saml.exceptions.SAMLException;
import org.pac4j.saml.sso.SAML2ResponseValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.namespace.QName;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Class responsible for executing every required checks for validating a SAML response.
 * The method validate populates the given {@link ExtendedSAMLMessageContext}
 * with the correct SAML assertion and the corresponding nameID's Bearer subject if every checks succeeds.
 * 
 * @author Michael Remond
 * @since 1.5.0
 *
 */
public class SAML2DefaultResponseValidator implements SAML2ResponseValidator {

    private final static Logger logger = LoggerFactory.getLogger(SAML2DefaultResponseValidator.class);

    /* maximum skew in seconds between SP and IDP clocks */
    private int acceptedSkew = 120;

    /* maximum lifetime after a successfull authentication on an IDP */
    private int maximumAuthenticationLifetime = 3600;

    private final SAML2SignatureTrustEngineProvider signatureTrustEngineProvider;

    private final Decrypter decrypter;

    public SAML2DefaultResponseValidator(final SAML2SignatureTrustEngineProvider engine,
                                         final Decrypter decrypter,
                                         final Integer maximumAuthenticationLifetime) {
        this.signatureTrustEngineProvider = engine;
        this.decrypter = decrypter;

        if (maximumAuthenticationLifetime != null) {
            this.maximumAuthenticationLifetime = maximumAuthenticationLifetime.intValue();
        }
    }

    /**
     * Validates the SAML protocol response and the SAML SSO response.
     * The method decrypt encrypted assertions if any.
     *
     * @param context the context
     */
    @Override
    public Credentials validate(final ExtendedSAMLMessageContext context) {

        final MessageContext<SAMLObject> message = context.getProfileRequestContext().getInboundMessageContext();

        if (!(message instanceof Response)) {
            throw new SAMLException("Response instance is an unsupported type");
        }
        final Response response = (Response) message.getMessage();
        final SignatureTrustEngine engine = this.signatureTrustEngineProvider.build();
        validateSamlProtocolResponse(response, context, engine);

        if (decrypter != null) {
            decryptEncryptedAssertions(response, decrypter);
        }

        validateSamlSSOResponse(response, context, engine, decrypter);
        return buildSaml2Credentials(context);
    }

    protected Saml2Credentials buildSaml2Credentials(final ExtendedSAMLMessageContext context) {

        final NameID nameId = context.getSAMLSubjectNameIdentifierContext().getSAML2SubjectNameID();
        final Assertion subjectAssertion = context.getSubjectAssertion();

        final List<Attribute> attributes = new ArrayList<Attribute>();
        for (final AttributeStatement attributeStatement : subjectAssertion.getAttributeStatements()) {
            for (final Attribute attribute : attributeStatement.getAttributes()) {
                attributes.add(attribute);
            }
            if (attributeStatement.getEncryptedAttributes().size() > 0) {
                if (decrypter == null) {
                    logger.warn("Encrypted attributes returned, but no keystore was provided.");
                } else {
                    for (final EncryptedAttribute encryptedAttribute : attributeStatement.getEncryptedAttributes()) {
                        try {
                            attributes.add(decrypter.decrypt(encryptedAttribute));
                        } catch (DecryptionException e) {
                            logger.warn("Decryption of attribute failed, continue with the next one", e);
                        }
                    }
                }
            }
        }
        return new Saml2Credentials(nameId, attributes, subjectAssertion.getConditions(),
                SAML2Client.class.getSimpleName());
    }

    /**
     * Validates the SAML protocol response:
     *  - IssueInstant
     *  - Issuer
     *  - StatusCode
     *  - Signature
     *
     * @param response the response
     * @param context the context
     * @param engine the engine
     */
    protected void validateSamlProtocolResponse(final Response response, final ExtendedSAMLMessageContext context,
            final SignatureTrustEngine engine) {

        if (!isIssueInstantValid(response.getIssueInstant())) {
            throw new SAMLException("Response issue instant is too old or in the future");
        }

        // TODO add Destination and inResponseTo Validation

        if (response.getIssuer() != null) {
            validateIssuer(response.getIssuer(), context);
        }

        if (!StatusCode.SUCCESS.equals(response.getStatus().getStatusCode().getValue())) {
            String status = response.getStatus().getStatusCode().getValue();
            if (response.getStatus().getStatusMessage() != null) {
                status += " / " + response.getStatus().getStatusMessage().getMessage();
            }
            throw new SAMLException("Authentication response is not success ; actual " + status);
        }

        if (response.getSignature() != null) {
            final String entityId = context.getSubcontext(SAMLPeerEntityContext.class).getEntityId();
            validateSignature(response.getSignature(), entityId, engine);
            context.getSubcontext(SAMLPeerEntityContext.class).setAuthenticated(true);
        }

    }

    /**
     * Validates the SAML SSO response by finding a valid assertion with authn statements.
     * Populates the {@link ExtendedSAMLMessageContext} with a subjectAssertion and a subjectNameIdentifier.
     *
     * @param response the response
     * @param context the context
     * @param engine the engine
     * @param decrypter the decrypter
     */
    protected void validateSamlSSOResponse(final Response response, final ExtendedSAMLMessageContext context,
            final SignatureTrustEngine engine, final Decrypter decrypter) {

        for (final Assertion assertion : response.getAssertions()) {
            if (assertion.getAuthnStatements().size() > 0) {
                try {
                    validateAssertion(assertion, context, engine, decrypter);
                } catch (final SAMLException e) {
                    logger.error("Current assertion validation failed, continue with the next one", e);
                    continue;
                }
                context.setSubjectAssertion(assertion);
                break;
            }
        }

        if (context.getSubjectAssertion() == null) {
            throw new SAMLException("No valid subject assertion found in response");
        }

        // We do not check EncryptedID here because it has been already decrypted and stored into NameID
        final List<SubjectConfirmation> subjectConfirmations = context.getSubjectConfirmations();
        final NameID nameIdentifier = (NameID) context.getSubcontext(SAMLSubjectNameIdentifierContext.class).getSubjectNameIdentifier();
        if ((nameIdentifier.getValue() == null) && (context.getBaseID() == null)
                && ((subjectConfirmations == null) || (subjectConfirmations.size() == 0))) {
            throw new SAMLException(
                    "Subject NameID, BaseID and EncryptedID cannot be all null at the same time if there are no Subject Confirmations.");
        }
    }

    /**
     * Decrypt encrypted assertions and add them to the assertions list of the response.
     *
     * @param response the response
     * @param decrypter the decrypter
     */
    protected void decryptEncryptedAssertions(final Response response, final Decrypter decrypter) {

        for (final EncryptedAssertion encryptedAssertion : response.getEncryptedAssertions()) {
            try {
                final Assertion decryptedAssertion = decrypter.decrypt(encryptedAssertion);
                response.getAssertions().add(decryptedAssertion);
            } catch (DecryptionException e) {
                logger.error("Decryption of assertion failed, continue with the next one", e);
            }
        }

    }

    /**
     * Validate issuer format and value.
     *
     * @param issuer the issuer
     * @param context the context
     */
    protected void validateIssuer(final Issuer issuer, final ExtendedSAMLMessageContext context) {
        if (issuer.getFormat() != null && !issuer.getFormat().equals(NameIDType.ENTITY)) {
            throw new SAMLException("Issuer type is not entity but " + issuer.getFormat());
        }

        final String entityId = context.getSubcontext(SAMLPeerEntityContext.class).getEntityId();
        if (!entityId.equals(issuer.getValue())) {
            throw new SAMLException("Issuer " + issuer.getValue() + " does not match idp entityId " + entityId);
        }
    }

    /**
     * Validate the given assertion:
     *  - issueInstant
     *  - issuer
     *  - subject
     *  - conditions
     *  - authnStatements
     *  - signature
     *
     * @param assertion the assertion
     * @param context the context
     * @param engine the engine
     * @param decrypter the decrypter
     */
    protected void validateAssertion(final Assertion assertion, final ExtendedSAMLMessageContext context,
            final SignatureTrustEngine engine, final Decrypter decrypter) {

        if (!isIssueInstantValid(assertion.getIssueInstant())) {
            throw new SAMLException("Assertion issue instant is too old or in the future");
        }

        validateIssuer(assertion.getIssuer(), context);

        if (assertion.getSubject() != null) {
            validateSubject(assertion.getSubject(), context, decrypter);
        } else {
            throw new SAMLException("Assertion subject cannot be null");
        }

        validateAssertionConditions(assertion.getConditions(), context);

        validateAuthenticationStatements(assertion.getAuthnStatements(), context);

        validateAssertionSignature(assertion.getSignature(), context, engine);

    }

    /**
     * Validate the given subject by finding a valid Bearer confirmation. If the subject is valid, put its nameID in the context.
     * 
     * NameID / BaseID / EncryptedID is first looked up directly in the Subject. If not present there, then all relevant
     * SubjectConfirmations are parsed and the IDs are taken from them.
     * 
     * @param subject
     *            The Subject from an assertion.
     * @param context
     *            SAML message context.
     * @param decrypter
     *            Decrypter used to decrypt some encrypted IDs, if they are present. May be {@code null}, no decryption will be possible
     *            then.
     */
    @SuppressWarnings("unchecked")
    protected void validateSubject(final Subject subject, final ExtendedSAMLMessageContext context,
            final Decrypter decrypter) {
        boolean samlIDFound = false;

        // Read NameID/BaseID/EncryptedID from the subject. If not present directly in the subject, try to find it in subject confirmations.
        NameID nameIdFromSubject = subject.getNameID();
        final BaseID baseIdFromSubject = subject.getBaseID();
        final EncryptedID encryptedIdFromSubject = subject.getEncryptedID();

        // Encrypted ID can overwrite the non-encrypted one, if present
        final NameID decryptedNameIdFromSubject = decryptEncryptedId(encryptedIdFromSubject, decrypter);
        if (decryptedNameIdFromSubject != null) {
            nameIdFromSubject = decryptedNameIdFromSubject;
        }

        // If we have a Name ID or a Base ID, we are fine :-)
        // If we don't have anything, let's go through all subject confirmations and get the IDs from them. At least one should be present but we don't care at this point.
        if (nameIdFromSubject != null || baseIdFromSubject != null) {

            final NameID nameIdentifier = (NameID) context.getSubcontext(SAMLSubjectNameIdentifierContext.class)
                    .getSubjectNameIdentifier();
            nameIdentifier.setValue(nameIdFromSubject.getValue());
            context.setBaseID(baseIdFromSubject);
            samlIDFound = true;
        }

        for (final SubjectConfirmation confirmation : subject.getSubjectConfirmations()) {
            if (SubjectConfirmation.METHOD_BEARER.equals(confirmation.getMethod())) {
                if (isValidBearerSubjectConfirmationData(confirmation.getSubjectConfirmationData(), context)) {
                    NameID nameIDFromConfirmation = confirmation.getNameID();
                    final BaseID baseIDFromConfirmation = confirmation.getBaseID();
                    final EncryptedID encryptedIDFromConfirmation = confirmation.getEncryptedID();

                    // Encrypted ID can overwrite the non-encrypted one, if present
                    final NameID decryptedNameIdFromConfirmation = decryptEncryptedId(encryptedIDFromConfirmation,
                            decrypter);
                    if (decryptedNameIdFromConfirmation != null) {
                        nameIDFromConfirmation = decryptedNameIdFromConfirmation;
                    }

                    if (!samlIDFound && (nameIDFromConfirmation != null || baseIDFromConfirmation != null)) {

                        final NameID nameIdentifier = (NameID) context.getSubcontext(SAMLSubjectNameIdentifierContext.class)
                                .getSubjectNameIdentifier();
                        nameIdentifier.setValue(nameIDFromConfirmation.getValue());
                        context.setBaseID(baseIDFromConfirmation);
                        context.getSubjectConfirmations().add(confirmation);
                        samlIDFound = true;
                    }
                    if (!samlIDFound) {
                        logger.warn("Could not find any Subject NameID/BaseID/EncryptedID, neither directly in the Subject nor in any Subject Confirmation.");
                    }
                    return;
                }
            }
        }

        throw new SAMLException("Subject confirmation validation failed");
    }

    /**
     * Decrypts an EncryptedID, using a decrypter.
     * 
     * @param encryptedId The EncryptedID to be decrypted.
     * @param decrypter The decrypter to use.
     * 
     * @return Decrypted ID or {@code null} if any input is {@code null}.
     * 
     * @throws SAMLException If the input ID cannot be decrypted.
     */
    protected NameID decryptEncryptedId(final EncryptedID encryptedId, final Decrypter decrypter) throws SAMLException {
        if (encryptedId == null) {
            return null;
        }
        if (decrypter == null) {
            logger.warn("Encrypted attributes returned, but no keystore was provided.");
            return null;
        }

        try {
            final NameID decryptedId = (NameID) decrypter.decrypt(encryptedId);
            return decryptedId;
        } catch (DecryptionException e) {
            throw new SAMLException("Decryption of an EncryptedID failed.", e);
        }
    }

    /**
     * Validate Bearer subject confirmation data
     *  - notBefore
     *  - NotOnOrAfter
     *  - recipient
     *
     * @param data the data
     * @param context the context
     * @return true if all Bearer subject checks are passing
     */
    protected boolean isValidBearerSubjectConfirmationData(final SubjectConfirmationData data,
            final ExtendedSAMLMessageContext context) {
        if (data == null) {
            logger.debug("SubjectConfirmationData cannot be null for Bearer confirmation");
            return false;
        }

        // TODO Validate inResponseTo

        if (data.getNotBefore() != null) {
            logger.debug("SubjectConfirmationData notBefore must be null for Bearer confirmation");
            return false;
        }

        if (data.getNotOnOrAfter() == null) {
            logger.debug("SubjectConfirmationData notOnOrAfter cannot be null for Bearer confirmation");
            return false;
        }

        if (data.getNotOnOrAfter().plusSeconds(acceptedSkew).isBeforeNow()) {
            logger.debug("SubjectConfirmationData notOnOrAfter is too old");
            return false;
        }

        if (data.getRecipient() == null) {
            logger.debug("SubjectConfirmationData recipient cannot be null for Bearer confirmation");
            return false;
        } else {
            if (!data.getRecipient().equals(context.getAssertionConsumerUrl())) {
                logger.debug("SubjectConfirmationData recipient {} does not match SP assertion consumer URL, found",
                        data.getRecipient());
                return false;
            }
        }
        return true;
    }

    /**
     * Validate assertionConditions
     *  - notBefore
     *  - notOnOrAfter
     *
     * @param conditions the conditions
     * @param context the context
     */
    protected void validateAssertionConditions(final Conditions conditions, final ExtendedSAMLMessageContext context) {

        if (conditions == null) {
            throw new SAMLException("Assertion conditions cannot be null");
        }

        if (conditions.getNotBefore() != null) {
            if (conditions.getNotBefore().minusSeconds(acceptedSkew).isAfterNow()) {
                throw new SAMLException("Assertion condition notBefore is not valid");
            }
        }

        if (conditions.getNotOnOrAfter() != null) {
            if (conditions.getNotOnOrAfter().plusSeconds(acceptedSkew).isBeforeNow()) {
                throw new SAMLException("Assertion condition notOnOrAfter is not valid");
            }
        }

        String entityId = context.getSubcontext(SAMLSelfEntityContext.class).getEntityId();
        validateAudienceRestrictions(conditions.getAudienceRestrictions(), entityId);

    }

    /**
     * Validate audience by matching the SP entityId.
     *
     * @param audienceRestrictions the audience restrictions
     * @param spEntityId the sp entity id
     */
    protected void validateAudienceRestrictions(final List<AudienceRestriction> audienceRestrictions,
            final String spEntityId) {

        if (audienceRestrictions == null || audienceRestrictions.size() == 0) {
            throw new SAMLException("Audience restrictions cannot be null or empty");
        }

        final Set<String> audienceUris = new HashSet<String>();
        for (final AudienceRestriction audienceRestriction : audienceRestrictions) {
            if (audienceRestriction.getAudiences() != null) {
                for (final Audience audience : audienceRestriction.getAudiences()) {
                    audienceUris.add(audience.getAudienceURI());
                }
            }
        }
        if (!audienceUris.contains(spEntityId)) {
            throw new SAMLException("Assertion audience " + audienceUris + " does not match SP configuration "
                    + spEntityId);
        }
    }

    /**
     * Validate the given authnStatements:
     *  - authnInstant
     *  - sessionNotOnOrAfter
     *
     * @param authnStatements the authn statements
     * @param context the context
     */
    protected void validateAuthenticationStatements(final List<AuthnStatement> authnStatements,
            final ExtendedSAMLMessageContext context) {

        for (AuthnStatement statement : authnStatements) {
            if (!isAuthnInstantValid(statement.getAuthnInstant())) {
                throw new SAMLException("Authentication issue instant is too old or in the future");
            }
            if (statement.getSessionNotOnOrAfter() != null && statement.getSessionNotOnOrAfter().isBeforeNow()) {
                throw new SAMLException("Authentication session between IDP and subject has ended");
            }
            // TODO implement authnContext validation
        }
    }

    /**
     * Validate assertion signature. If none is found and the SAML response did not have one and the SP requires
     * the assertions to be signed, the validation fails.
     *
     * @param signature the signature
     * @param context the context
     * @param engine the engine
     */
    protected void validateAssertionSignature(final Signature signature, final ExtendedSAMLMessageContext context,
            final SignatureTrustEngine engine) {

        final SAMLSelfEntityContext selfContext = context.getSAMLSelfEntityContext();
        final SAMLPeerEntityContext peerContext = context.getSAMLPeerEntityContext();

        final QName role = selfContext.getRole();
        if (signature != null) {
            final String entityId = peerContext.getEntityId();
            validateSignature(signature, entityId, engine);
        } else if (((SPSSODescriptor) role).getWantAssertionsSigned()
                && !peerContext.isAuthenticated()) {
            throw new SAMLException("Assertion or response must be signed");
        }
    }

    /**
     * Validate the given digital signature by checking its profile and value.
     *
     * @param signature the signature
     * @param idpEntityId the idp entity id
     * @param trustEngine the trust engine
     */
    protected void validateSignature(final Signature signature, final String idpEntityId,
            final SignatureTrustEngine trustEngine) {

        final SAMLSignatureProfileValidator validator = new SAMLSignatureProfileValidator();
        try {
            validator.validate(signature);
        } catch (SignatureException e) {
            throw new SAMLException("SAMLSignatureProfileValidator failed to validate signature", e);
        }

        final CriteriaSet criteriaSet = new CriteriaSet();
        criteriaSet.add(new UsageCriterion(UsageType.SIGNING));
        criteriaSet.add(new EntityRoleCriterion(IDPSSODescriptor.DEFAULT_ELEMENT_NAME));
        criteriaSet.add(new ProtocolCriterion(SAMLConstants.SAML20P_NS));
        criteriaSet.add(new EntityIdCriterion(idpEntityId));

        boolean valid = false;
        try {
            valid = trustEngine.validate(signature, criteriaSet);
        } catch (SecurityException e) {
            throw new SAMLException("An error occured during signature validation", e);
        }
        if (!valid) {
            throw new SAMLException("Signature is not trusted");
        }
    }

    private boolean isDateValid(final DateTime issueInstant, int interval) {
        long now = System.currentTimeMillis();
        return issueInstant.isBefore(now + acceptedSkew * 1000)
                && issueInstant.isAfter(now - (acceptedSkew + interval) * 1000);
    }

    private boolean isIssueInstantValid(final DateTime issueInstant) {
        return isDateValid(issueInstant, 0);
    }

    private boolean isAuthnInstantValid(DateTime authnInstant) {
        return isDateValid(authnInstant, maximumAuthenticationLifetime);
    }

    public void setAcceptedSkew(final int acceptedSkew) {
        this.acceptedSkew = acceptedSkew;
    }

    public void setMaximumAuthenticationLifetime(int maximumAuthenticationLifetime) {
        this.maximumAuthenticationLifetime = maximumAuthenticationLifetime;
    }

}
