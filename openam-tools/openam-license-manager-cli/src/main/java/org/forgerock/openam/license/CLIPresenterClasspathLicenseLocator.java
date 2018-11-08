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
* Copyright 2014-2017 ForgeRock AS.
*/

package org.forgerock.openam.license;

import java.nio.charset.Charset;

/**
 * Loads required licenses from the classpath with well-known names for presentation on a CLI.
 *
 * @see ClassLoader#getSystemResourceAsStream(String)
 * @since 12.0.0
 */
public class CLIPresenterClasspathLicenseLocator extends ClasspathLicenseLocator {

    final static String[] LICENSES = { "license.txt" };

    /**
     * No args constructor to be called via Guice.
     */
    public CLIPresenterClasspathLicenseLocator() {
        this(Thread.currentThread().getContextClassLoader(), Charset.forName("UTF-8"));
    }

    /**
     * Constructs a CLI presenter classpath license locator with the given classloader, charset
     * and list of license files to load.
     *
     * @param classLoader the classloader to use for locating licenses on the classpath.
     * @param charset the charset to use for decoding license files.
     */
    public CLIPresenterClasspathLicenseLocator(ClassLoader classLoader, Charset charset) {
        super(classLoader, charset, LICENSES);
    }
}
