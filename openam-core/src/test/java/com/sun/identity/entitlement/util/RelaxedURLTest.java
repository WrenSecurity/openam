/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2013-2017 ForgeRock AS.
 */

package com.sun.identity.entitlement.util;

import static org.fest.assertions.Assertions.assertThat;

import java.net.MalformedURLException;

import org.testng.annotations.Test;

/**
 * Unit test to exercise the RelaxedURL behaviour.
 */
public final class RelaxedURLTest {

    @Test
    public void whenGivenAFullUrlAllPartsAreParsedAppropriately() throws MalformedURLException {
        // Given
        RelaxedURL url = new RelaxedURL("http://www.test.com:123/hello?world=456");

        // Then
        assertThat(url.getProtocol()).isEqualTo("http");
        assertThat(url.getHostname()).isEqualTo("www.test.com");
        assertThat(url.getPort()).isEqualTo("123");
        assertThat(url.getPath()).isEqualTo("/hello");
        assertThat(url.getQuery()).isEqualTo("world=456");
        assertThat(url.toString()).isEqualTo("http://www.test.com:123/hello?world=456");
    }

    @Test
    public void whenNoProtocolIsGivenRemainsBlank() throws MalformedURLException {
        // Given
        RelaxedURL url = new RelaxedURL("://www.test.com:123/hello?world=456");

        // Then
        assertThat(url.getProtocol()).isEmpty();
        assertThat(url.getHostname()).isEqualTo("www.test.com");
        assertThat(url.getPort()).isEqualTo("123");
        assertThat(url.getPath()).isEqualTo("/hello");
        assertThat(url.getQuery()).isEqualTo("world=456");
        assertThat(url.toString()).isEqualTo("://www.test.com:123/hello?world=456");
    }

    @Test
    public void whenNoHostIsGivenRemainsBlank() throws MalformedURLException {
        // Given
        RelaxedURL url = new RelaxedURL("http://:123/hello?world=456");

        // Then
        assertThat(url.getProtocol()).isEqualTo("http");
        assertThat(url.getHostname()).isEmpty();
        assertThat(url.getPort()).isEqualTo("123");
        assertThat(url.getPath()).isEqualTo("/hello");
        assertThat(url.getQuery()).isEqualTo("world=456");
        assertThat(url.toString()).isEqualTo("http://:123/hello?world=456");
    }

    @Test
    public void whenNoPortIsGivenForNonTlsPortIsAdded() throws MalformedURLException {
        // Given
        RelaxedURL url = new RelaxedURL("http://www.test.com/hello?world=456");

        // Then
        assertThat(url.getProtocol()).isEqualTo("http");
        assertThat(url.getHostname()).isEqualTo("www.test.com");
        assertThat(url.getPort()).isEqualTo("80");
        assertThat(url.getPath()).isEqualTo("/hello");
        assertThat(url.getQuery()).isEqualTo("world=456");
        assertThat(url.toString()).isEqualTo("http://www.test.com:80/hello?world=456");
    }

    @Test
    public void whenNoPortIsGivenForTlsPortIsAdded() throws MalformedURLException {
        // Given
        RelaxedURL url = new RelaxedURL("https://www.test.com/hello?world=456");

        // Then
        assertThat(url.getProtocol()).isEqualTo("https");
        assertThat(url.getHostname()).isEqualTo("www.test.com");
        assertThat(url.getPort()).isEqualTo("443");
        assertThat(url.getPath()).isEqualTo("/hello");
        assertThat(url.getQuery()).isEqualTo("world=456");
        assertThat(url.toString()).isEqualTo("https://www.test.com:443/hello?world=456");
    }

    @Test
    public void whenNoPortIsGivenButProtocolIsUnknownPortRemainsBlank() throws MalformedURLException {
        // Given
        RelaxedURL url = new RelaxedURL("abc://www.test.com/hello?world=456");

        // Then
        assertThat(url.getProtocol()).isEqualTo("abc");
        assertThat(url.getHostname()).isEqualTo("www.test.com");
        assertThat(url.getPort()).isEmpty();
        assertThat(url.getPath()).isEqualTo("/hello");
        assertThat(url.getQuery()).isEqualTo("world=456");
        assertThat(url.toString()).isEqualTo("abc://www.test.com/hello?world=456");
    }

    @Test
    public void whenNoPathIsGivenDefaultsToRoot() throws MalformedURLException {
        // Given
        RelaxedURL url = new RelaxedURL("http://www.test.com:123/?world=456");

        // Then
        assertThat(url.getProtocol()).isEqualTo("http");
        assertThat(url.getHostname()).isEqualTo("www.test.com");
        assertThat(url.getPort()).isEqualTo("123");
        assertThat(url.getPath()).isEqualTo("/");
        assertThat(url.getQuery()).isEqualTo("world=456");
        assertThat(url.toString()).isEqualTo("http://www.test.com:123/?world=456");
    }

    @Test
    public void whenNoQueryStringIsGivenRemainsBlank() throws MalformedURLException {
        // Given
        RelaxedURL url = new RelaxedURL("http://www.test.com:123/hello");

        // Then
        assertThat(url.getProtocol()).isEqualTo("http");
        assertThat(url.getHostname()).isEqualTo("www.test.com");
        assertThat(url.getPort()).isEqualTo("123");
        assertThat(url.getPath()).isEqualTo("/hello");
        assertThat(url.getQuery()).isEmpty();
        assertThat(url.toString()).isEqualTo("http://www.test.com:123/hello");
    }

    @Test
    public void whenAFullWildcardUrlPatternIsGivenAllPartsParsedAppropriately() throws MalformedURLException {
        // Given
        RelaxedURL url = new RelaxedURL("*://*:*/*?*");

        // Then
        assertThat(url.getProtocol()).isEqualTo("*");
        assertThat(url.getHostname()).isEqualTo("*");
        assertThat(url.getPort()).isEqualTo("*");
        assertThat(url.getPath()).isEqualTo("/*");
        assertThat(url.getQuery()).isEqualTo("*");
        assertThat(url.toString()).isEqualTo("*://*:*/*?*");
    }

    @Test(expectedExceptions = MalformedURLException.class)
    public void whenAMalformedUrlIsGivenExceptionIsThrown() throws MalformedURLException {
        // Given
        new RelaxedURL("ab:cd:ef");
    }

}
