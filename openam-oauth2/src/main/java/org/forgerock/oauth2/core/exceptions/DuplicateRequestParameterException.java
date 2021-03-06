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
 * Copyright 2015-2016 ForgeRock AS.
 */

package org.forgerock.oauth2.core.exceptions;


import org.forgerock.openam.oauth2.OAuth2Constants.UrlLocation;

/**
 * Thrown when the duplicate request parameter is found.
 *
 * @since 13.5.0
 */
public class DuplicateRequestParameterException extends OAuth2Exception {



    /**
     * Constructs a new DuplicateRequestParameterException with the default message.
     */
    public DuplicateRequestParameterException() {
        this("The request has a duplicate request parameter.");
    }

    /**
     * Constructs a new DuplicateRequestParameterException with the specified message.
     * The {@link UrlLocation} for the parameters are defaulted to QUERY.
     *
     * @param message The reason for the exception.
     */
    public DuplicateRequestParameterException(final String message) {
        this(message, UrlLocation.QUERY);
    }

    /**
     * Constructs a new DuplicateRequestParameterException with the specified message.
     *
     * @param message The reason for the exception.
     * @param parameterLocation Indicates the location of the parameters in the URL.
     */
    public DuplicateRequestParameterException(final String message, final UrlLocation parameterLocation) {
        super(400, "invalid_request", message, parameterLocation);
    }
}
