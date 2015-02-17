/**
 * Copyright (c) Codice Foundation
 *
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 *
 **/
package org.codice.ddf.cxf;

import ddf.security.PropertiesLoader;
import ddf.security.service.SecurityServiceException;
import ddf.security.settings.SecuritySettingsService;
import ddf.security.sts.client.configuration.STSClientConfiguration;
import org.apache.commons.lang.StringUtils;
import org.apache.cxf.Bus;
import org.apache.cxf.BusException;
import org.apache.cxf.common.util.CollectionUtils;
import org.apache.cxf.configuration.jsse.TLSClientParameters;
import org.apache.cxf.endpoint.EndpointException;
import org.apache.cxf.interceptor.LoggingInInterceptor;
import org.apache.cxf.interceptor.LoggingOutInterceptor;
import org.apache.cxf.jaxrs.client.Client;
import org.apache.cxf.jaxrs.client.ClientConfiguration;
import org.apache.cxf.jaxrs.client.JAXRSClientFactoryBean;
import org.apache.cxf.jaxrs.client.WebClient;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.transport.https.HttpsURLConnectionFactory;
import org.apache.cxf.ws.security.SecurityConstants;
import org.apache.cxf.ws.security.tokenstore.SecurityToken;
import org.apache.cxf.ws.security.trust.STSClient;
import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

import javax.ws.rs.core.Cookie;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class SecureCxfClientFactoryBuilder {

    protected static final String ADDRESSING_NAMESPACE = "http://www.w3.org/2005/08/addressing";

    private static final Logger LOGGER = LoggerFactory
            .getLogger(SecureCxfClientFactoryBuilder.class);

    private final SecuritySettingsService securitySettingsService;

    private final STSClientConfiguration stsClientConfig;

    /**
     * Blueprint constructor.
     */
    public SecureCxfClientFactoryBuilder(SecuritySettingsService securitySettingsService,
            STSClientConfiguration stsClientConfig) throws SecurityServiceException {
        if (securitySettingsService == null || stsClientConfig == null) {
            throw new SecurityServiceException("Could not access security configurations.");
        }
        this.securitySettingsService = securitySettingsService;
        this.stsClientConfig = stsClientConfig;
    }

    /**
     * @see SecureCxfClientFactoryBuilder#buildFactory(String, Class, String, String, java.util.List, boolean)
     */
    public <T> SecureCxfClientFactory<T> buildFactory(String endpointUrl, Class<T> interfaceClass)
            throws SecurityServiceException {
        return buildFactory(endpointUrl, interfaceClass, null, null);
    }

    /**
     * @see SecureCxfClientFactoryBuilder#buildFactory(String, Class, String, String, java.util.List, boolean)
     */
    public <T> SecureCxfClientFactory<T> buildFactory(String endpointUrl, Class<T> interfaceClass,
            String username, String password) throws SecurityServiceException {
        return buildFactory(endpointUrl, interfaceClass, username, password, null, false);
    }

    /**
     * Creates a factory that will return security-aware clients.
     *
     * @see SecureCxfClientFactoryBuilder.SecureCxfClientFactory
     */
    public <T> SecureCxfClientFactory<T> buildFactory(String endpointUrl, Class<T> interfaceClass,
            String username, String password, List<?> providers, boolean disableCnCheck)
            throws SecurityServiceException {
        if (StringUtils.isEmpty(endpointUrl) || interfaceClass == null) {
            throw new IllegalArgumentException(
                    "Called without a valid URL, will not be able to connect.");
        }

        JAXRSClientFactoryBean jaxrsClientFactoryBean = new JAXRSClientFactoryBean();
        jaxrsClientFactoryBean.setServiceClass(interfaceClass);
        jaxrsClientFactoryBean.setAddress(endpointUrl);
        jaxrsClientFactoryBean.setClassLoader(interfaceClass.getClassLoader());
        jaxrsClientFactoryBean.getInInterceptors().add(new LoggingInInterceptor());
        jaxrsClientFactoryBean.getOutInterceptors().add(new LoggingOutInterceptor());

        if (!CollectionUtils.isEmpty(providers)) {
            jaxrsClientFactoryBean.setProviders(providers);
        }

        Client cxfClient = WebClient.client(jaxrsClientFactoryBean.create(interfaceClass));
        if (cxfClient == null) {
            throw new SecurityServiceException("Could not construct base client");
        }
        ClientConfiguration clientConfig = WebClient.getConfig(cxfClient);

        if (!StringUtils.startsWithIgnoreCase(endpointUrl, "https")) {
            LOGGER.warn("Cannot secure non-https connection " + endpointUrl
                    + ", only unsecured clients will be created");
        } else {
            initSecurity(clientConfig, cxfClient, username, password);
        }

        if (disableCnCheck) {
            disableCnCheck(clientConfig);
        }

        return new SecureCxfClientFactory<>(securitySettingsService, stsClientConfig, cxfClient);
    }

    private void disableCnCheck(ClientConfiguration clientConfig) throws SecurityServiceException {
        HTTPConduit httpConduit = clientConfig.getHttpConduit();
        if (httpConduit == null) {
            throw new SecurityServiceException(
                    "HTTPConduit was null for " + this + ". Unable to disable CN Check");
        }

        TLSClientParameters tlsParams = httpConduit.getTlsClientParameters();

        tlsParams.setDisableCNCheck(true);
    }

    /*
     * Add TLS and Basic Auth credentials to the underlying {@link org.apache.cxf.transport.http.HTTPConduit}
     * This includes two-way ssl assuming that the platform keystores are configured correctly
     */
    private void initSecurity(ClientConfiguration clientConfig, Client cxfClient, String username,
            String password) throws SecurityServiceException {

        HTTPConduit httpConduit = clientConfig.getHttpConduit();
        if (httpConduit == null) {
            throw new SecurityServiceException(
                    "HTTPConduit was null for " + this + ". Unable to configure security.");
        }

        if (StringUtils.isNotEmpty(username) && StringUtils.isNotEmpty(password)) {
            if (httpConduit.getAuthorization() != null) {
                httpConduit.getAuthorization().setUserName(username);
                httpConduit.getAuthorization().setPassword(password);
            }
        }

        TLSClientParameters tlsParams = securitySettingsService.getTLSParameters();
        httpConduit.setTlsClientParameters(tlsParams);
    }

    public class SecureCxfClientFactory<T> {

        private final SecuritySettingsService securitySettingsService;

        private final STSClientConfiguration stsClientConfig;

        private Client cxfClient;

        private SecureCxfClientFactory(SecuritySettingsService securitySettingsService,
                STSClientConfiguration stsClientConfig, Client cxfClient) {
            this.securitySettingsService = securitySettingsService;
            this.stsClientConfig = stsClientConfig;
            this.cxfClient = cxfClient;
        }

        /**
         * Clients produced by this method will be secured with basic authentication
         * (if a username and password were provided), two-way ssl,
         * and the provided security subject.
         * <p/>
         * The returned client should NOT be reused between requests!
         * This method should be called for each new request in order to ensure
         * that the security subject is up-to-date each time.
         *
         * @see SecureCxfClientFactoryBuilder
         */
        public T getClientForSubject(Subject subject) throws SecurityServiceException {
            String asciiString = cxfClient.getBaseURI().toASCIIString();
            if (!StringUtils.startsWithIgnoreCase(asciiString, "https")) {
                throw new SecurityServiceException(
                        "Cannot secure non-https connection " + asciiString);
            }

            WebClient newClient = WebClient.fromClient(cxfClient);

            if (subject instanceof ddf.security.Subject) {
                RestSecurity.setSubjectOnClient((ddf.security.Subject) subject, newClient);
            } else {
                throw new SecurityServiceException("Not a ddf subject " + subject);
            }
            WebClient.getConfig(newClient).getRequestContext()
                    .put(org.apache.cxf.message.Message.MAINTAIN_SESSION, Boolean.TRUE);

            return (T) newClient;
        }

        /**
         * Clients produced by this method will be secured with basic authentication
         * (if a username and password were provided), two-way ssl,
         * and the system security token (x509 cert).
         */
        public T getClientForSystem() throws SecurityServiceException {
            String asciiString = cxfClient.getBaseURI().toASCIIString();
            if (!StringUtils.startsWithIgnoreCase(asciiString, "https")) {
                throw new SecurityServiceException(
                        "Cannot secure non-https connection " + asciiString);
            }

            if (StringUtils.isBlank(stsClientConfig.getAddress())) {
                throw new SecurityServiceException(
                        "STSClientConfiguration is either null or its address is blank - assuming no STS Client is configured, so no SAML assertion will get generated.");
            }
            WebClient newClient = WebClient.fromClient(cxfClient);
            ClientConfiguration clientConfig = WebClient.getConfig(newClient);
            Bus clientBus = clientConfig.getBus();
            STSClient stsClient = configureSTSClient(clientBus);

            try {
                SecurityToken securityToken = stsClient
                        .requestSecurityToken(stsClientConfig.getAddress());
                Element samlToken = securityToken.getToken();
                if (samlToken != null) {
                    Cookie cookie = new Cookie(ddf.security.SecurityConstants.SAML_COOKIE_NAME,
                            RestSecurity.encodeSaml(samlToken));
                    newClient.reset();
                    newClient.cookie(cookie);
                    return (T) newClient;
                } else {
                    throw new SecurityServiceException(
                            "Attempt to retrieve SAML token resulted in null token - could not add token to request");
                }
            } catch (Exception e) {
                throw new SecurityServiceException("Exception trying to get SAML assertion", e);
            }
        }

        /**
         * Returns a new STSClient object configured with the properties that have
         * been set.
         *
         * @param bus - CXF bus to initialize STSClient with
         * @return STSClient
         */
        private STSClient configureSTSClient(Bus bus) throws SecurityServiceException {

            String stsAddress = stsClientConfig.getAddress();
            String stsServiceName = stsClientConfig.getServiceName();
            String stsEndpointName = stsClientConfig.getEndpointName();
            String signaturePropertiesPath = stsClientConfig.getSignatureProperties();
            String encryptionPropertiesPath = stsClientConfig.getEncryptionProperties();
            String stsPropertiesPath = stsClientConfig.getTokenProperties();

            STSClient stsClient = new STSClient(bus);

            LOGGER.debug("Setting WSDL location (stsAddress) on STSClient: {}", stsAddress);
            stsClient.setWsdlLocation(stsAddress);
            LOGGER.debug("Setting service name on STSClient: {}", stsServiceName);
            stsClient.setServiceName(stsServiceName);
            LOGGER.debug("Setting endpoint name on STSClient: {}", stsEndpointName);
            stsClient.setEndpointName(stsEndpointName);
            LOGGER.debug("Setting addressing namespace on STSClient: {}", ADDRESSING_NAMESPACE);
            stsClient.setAddressingNamespace(ADDRESSING_NAMESPACE);

            Map<String, Object> newStsProperties = new HashMap<>();

            // Properties loader should be able to find the properties file
            // no matter where it is
            if (StringUtils.isNotEmpty(signaturePropertiesPath)) {
                LOGGER.debug("Setting signature properties on STSClient: {}",
                        signaturePropertiesPath);
                Properties signatureProperties = PropertiesLoader
                        .loadProperties(signaturePropertiesPath);
                newStsProperties.put(SecurityConstants.SIGNATURE_PROPERTIES, signatureProperties);
            }
            if (StringUtils.isNotEmpty(encryptionPropertiesPath)) {
                LOGGER.debug("Setting encryption properties on STSClient: {}",
                        encryptionPropertiesPath);
                Properties encryptionProperties = PropertiesLoader
                        .loadProperties(encryptionPropertiesPath);
                newStsProperties.put(SecurityConstants.ENCRYPT_PROPERTIES, encryptionProperties);
            }
            if (StringUtils.isNotEmpty(stsPropertiesPath)) {
                LOGGER.debug("Setting sts properties on STSClient: {}", stsPropertiesPath);
                Properties stsProperties = PropertiesLoader.loadProperties(stsPropertiesPath);
                newStsProperties.put(SecurityConstants.STS_TOKEN_PROPERTIES, stsProperties);
            }

            LOGGER.debug("Setting STS TOKEN USE CERT FOR KEY INFO to \"true\"");
            newStsProperties
                    .put(SecurityConstants.STS_TOKEN_USE_CERT_FOR_KEYINFO, Boolean.TRUE.toString());
            stsClient.setProperties(newStsProperties);

            if (stsClient.getWsdlLocation()
                    .startsWith(HttpsURLConnectionFactory.HTTPS_URL_PROTOCOL_ID)) {
                try {
                    LOGGER.debug("Setting up SSL on the STSClient HTTP Conduit");
                    HTTPConduit httpConduit = (HTTPConduit) stsClient.getClient().getConduit();
                    if (httpConduit == null) {
                        LOGGER.info(
                                "HTTPConduit was null for stsClient. Unable to configure keystores for stsClient.");
                    } else {
                        httpConduit
                                .setTlsClientParameters(securitySettingsService.getTLSParameters());
                    }
                } catch (BusException e) {
                    throw new SecurityServiceException("Unable to create sts client.", e);
                } catch (EndpointException e) {
                    throw new SecurityServiceException("Unable to create sts client endpoint.", e);
                }
            }

            stsClient.setTokenType(stsClientConfig.getAssertionType());
            stsClient.setKeyType(stsClientConfig.getKeyType());
            stsClient.setKeySize(Integer.valueOf(stsClientConfig.getKeySize()));

            return stsClient;
        }
    }
}
