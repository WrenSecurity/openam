/**
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2009 Sun Microsystems Inc. All Rights Reserved
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * https://opensso.dev.java.net/public/CDDLv1.0.html or
 * opensso/legal/CDDLv1.0.txt
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at opensso/legal/CDDLv1.0.txt.
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 * $Id: RelaxedURL.java,v 1.2 2009/10/20 18:46:16 veiming Exp $
 *
 * Portions Copyrighted 2013-2017 ForgeRock AS.
 */
package com.sun.identity.entitlement.util;

import java.net.MalformedURLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Relaxed URL can parse out standard URLs but also URL patterns that may include wildcards.
 * <p>
 * Example standard URL: {@code http://abc.def.com:8080/xyz?a=b&1=2}
 * Example wildcard URL: {@code *://*:80/*?*}
 */
public final class RelaxedURL {

    private static final Pattern SIMPLE_URL_PATTERN =
            Pattern.compile("^(?<protocol>.*)://(?<host>[^/:]*)(:(?<port>[^/?]*))?" +
                    "(?<path>/[^?]*)?(\\?(?<querystring>.*))?$");

    private static final String PROTOCOL_HTTPS = "https";
    private static final String PROTOCOL_HTTP = "http";

    private final String protocol;
    private final String hostname;
    private final String port;
    private final String path;
    private final String query;

    /**
     * Constructs a new relaxed URL from the given URL string.
     *
     * @param url URL string
     * @throws MalformedURLException should the passed URL string be malformed
     */
    public RelaxedURL(String url) throws MalformedURLException {
        Matcher matcher = SIMPLE_URL_PATTERN.matcher(url);

        if (!matcher.find()) {
            throw new MalformedURLException(url);
        }

        protocol = getGroupValueWithDefault("protocol", matcher);
        hostname = getGroupValueWithDefault("host", matcher);
        port = determinePort(getGroupValueWithDefault("port", matcher), protocol);
        path = getGroupValueWithDefault("path", matcher, "/");
        query = getGroupValueWithDefault("querystring", matcher);
    }

    private String determinePort(String parsedPort, String protocol) {
        if (!parsedPort.isEmpty()) {
            return parsedPort;
        }

        if (PROTOCOL_HTTP.equalsIgnoreCase(protocol)) {
            return "80";
        }

        if (PROTOCOL_HTTPS.equalsIgnoreCase(protocol)) {
            return "443";
        }

        return parsedPort;
    }

    private String getGroupValueWithDefault(String groupName, Matcher matcher) {
        return getGroupValueWithDefault(groupName, matcher, "");
    }

    private String getGroupValueWithDefault(String groupName, Matcher matcher, String defaultValue) {
        String groupValue = matcher.group(groupName);
        return groupValue == null ? defaultValue : groupValue;
    }

    /**
     * Returns the parsed protocol.
     *
     * @return the parsed protocol or empty string if not present
     */
    public String getProtocol() {
        return protocol;
    }

    /**
     * Returns the parsed hostname.
     *
     * @return the parsed hostname or empty string if not present
     */
    public String getHostname() {
        return hostname;
    }

    /**
     * Returns the parsed port.
     *
     * @return the parsed port or empty string if not present
     */
    public String getPort() {
        return port;
    }

    /**
     * Returns the parsed path.
     *
     * @return the parsed path or "/" if not present
     */
    public String getPath() {
        return path;
    }

    /**
     * Returns the parsed query string.
     *
     * @return the parsed query string or empty string if not present
     */
    public String getQuery() {
        return query;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append(protocol);
        builder.append("://");
        builder.append(hostname);

        if (!port.isEmpty()) {
            builder.append(':');
            builder.append(port);
        }

        builder.append(path);

        if (!query.isEmpty()) {
            builder.append('?');
            builder.append(query);
        }

        return builder.toString();
    }

}

