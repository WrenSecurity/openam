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
 * Copyright 2016 ForgeRock AS.
 */

package org.forgerock.openam.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.forgerock.util.encode.Base64;

/**
 * Utility class for assisting with the creation of hashes.
 *
 * @since 14.0.0
 */
public final class HashUtil {

    private static final String SHA_256 = "SHA-256";

    /**
     * Given some text, generates a SHA-256 hash, and returns it in base64 encoding.
     *
     * @param text text to be hashed
     * @return base64 encoded hash
     */
    public static String generateBase64Hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            digest.update(text.getBytes(StandardCharsets.UTF_8));
            return Base64.encode(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Error whilst generating hash", e);
        }
    }

}
