package org.pac4j.saml.transport;

import lombok.val;
import org.opensaml.messaging.decoder.MessageDecodingException;
import org.opensaml.saml.common.SAMLObject;
import org.opensaml.saml.common.binding.SAMLBindingSupport;
import org.opensaml.saml.common.xml.SAMLConstants;
import org.pac4j.core.context.CallContext;
import org.pac4j.core.context.WebContextHelper;
import org.pac4j.saml.context.SAML2MessageContext;
import org.pac4j.saml.util.SAML2Utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Decoder for messages sent via HTTP-Redirect binding.
 *
 * @author Jerome Leleu
 * @since 3.4.0
 */
public class Pac4jHTTPRedirectDeflateDecoder extends AbstractPac4jDecoder {

    public Pac4jHTTPRedirectDeflateDecoder(final CallContext context) {
        super(context);
    }

    @Override
    protected void doDecode() throws MessageDecodingException {
        val messageContext = new SAML2MessageContext(callContext);

        if (WebContextHelper.isGet(callContext.webContext())) {
            val relayState = this.callContext.webContext().getRequestParameter("RelayState").orElse(null);
            logger.debug("Decoded SAML relay state of: {}", relayState);
            SAMLBindingSupport.setRelayState(messageContext.getMessageContext(), relayState);

            val base64DecodedMessage = this.getBase64DecodedMessage();
            val inflatedMessage = inflate(base64DecodedMessage);
            val inboundMessage = (SAMLObject) this.unmarshallMessage(inflatedMessage);
            SAML2Utils.logProtocolMessage(inboundMessage);
            messageContext.getMessageContext().setMessage(inboundMessage);
            logger.debug("Decoded SAML message");
            this.populateBindingContext(messageContext);
            this.setMessageContext(messageContext.getMessageContext());
        } else {
            throw new MessageDecodingException("This message decoder only supports the HTTP-Redirect method");
        }
    }

    protected InputStream inflate(final byte[] input) throws MessageDecodingException {
        try {
            // compatible with GZIP and PKZIP
            return internalInflate(input, new Inflater(true));
        } catch (final IOException e) {
            try {
                // deflate compression only
                return internalInflate(input, new Inflater());
            } catch (final IOException e2) {
                logger.warn("Cannot inflate message, returning it as is");
                return new ByteArrayInputStream(input);
            }
        }
    }

    protected InputStream internalInflate(final byte[] input, final Inflater inflater) throws IOException {
        val baos = new ByteArrayOutputStream();
        val iis = new InflaterInputStream(new ByteArrayInputStream(input), inflater);
        try {
            val buffer = new byte[1000];
            int length;
            while ((length = iis.read(buffer)) > 0) {
                baos.write(buffer, 0, length);
            }
            val decodedBytes = baos.toByteArray();
            val decodedMessage = new String(decodedBytes, StandardCharsets.UTF_8);
            logger.debug("Inflated SAML message: {}", decodedMessage);
            return new ByteArrayInputStream(decodedBytes);
        } finally {
            baos.close();
            iis.close();
        }
    }

    @Override
    public String getBindingURI(final SAML2MessageContext messageContext) {
        return SAMLConstants.SAML2_REDIRECT_BINDING_URI;
    }
}
