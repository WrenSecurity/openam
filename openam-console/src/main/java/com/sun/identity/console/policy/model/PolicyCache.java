/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2007 Sun Microsystems Inc. All Rights Reserved
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
 * $Id: PolicyCache.java,v 1.2 2008/06/25 05:43:07 qcheng Exp $
 *
 * Portions Copyrighted 2011-2016 ForgeRock AS.
 */

package com.sun.identity.console.policy.model;

import static org.forgerock.openam.utils.Time.currentTimeMillis;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import com.iplanet.dpro.session.SessionException;
import com.iplanet.dpro.session.SessionID;
import com.iplanet.dpro.session.service.SessionService;
import com.iplanet.dpro.session.watchers.listeners.SessionDeletionListener;
import com.iplanet.sso.SSOToken;
import com.sun.identity.console.base.model.AMConsoleException;
import com.sun.identity.console.base.model.AMModelBase;
import com.sun.identity.shared.encode.Base64;
import org.forgerock.guava.common.cache.CacheBuilder;
import org.forgerock.guava.common.cache.CacheLoader;
import org.forgerock.guava.common.cache.LoadingCache;
import org.forgerock.guice.core.InjectorHolder;
import org.forgerock.openam.cts.continuous.watching.SessionWatchingNotSupported;

/* - NEED NOT LOG - */

/**
 * This class caches policy object for Console.  Token ID and a randomly
 * generated string are used as key to cache and retrieve a policy.
 */
public class PolicyCache implements SessionDeletionListener {

    /**
     * Singleton holder for the policy cache.
     */
    private enum Holder {
        INSTANCE;

        private PolicyCache cache;

        Holder() {
            SessionService sessionService = InjectorHolder.getInstance(SessionService.class);
            cache = new PolicyCache(sessionService);
            try {
                sessionService.registerListener(cache);
            } catch (SessionWatchingNotSupported ignored) {
            }
        }

        /**
         * Gets the policy cache instance.
         *
         * @return The {@code PolicyCache}.
         */
        public PolicyCache getCache() {
            return cache;
        }
    }

    private final LoadingCache<String, Map<String, CachedPolicy>> cache = CacheBuilder.newBuilder()
            .maximumSize(100)
            .build(new CacheLoader<String, Map<String, CachedPolicy>>() {
                public Map<String, CachedPolicy> load(String key) {
                    return new HashMap<>(10);
                }
            });

    /**
     * Gets an instance of policy cache.
     *
     * @return An instance of policy cache.
     */
    public static PolicyCache getInstance() {
        return Holder.INSTANCE.getCache();
    }

    /**
     * The generated random string is used to cache policy object when
     * we switch from on tab to another. Since it is used from caching 
     * purposes, usage of secure random is not required.
     */
    private final Random random = new Random();
    private final SessionService sessionService;

    private PolicyCache(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * Caches a policy object
     *
     * @param token single sign on token
     * @param policy Policy object to be cached
     * @return an unique key for retrieve this policy in future
     */
    public String cachePolicy(SSOToken token, CachedPolicy policy) {
        if (policy != null) {
            String key = token.getTokenID().toString();
            synchronized (cache) {
                Map<String, CachedPolicy> map = cache.getUnchecked(key);
                try {
                    sessionService.notifyListenerFor(new SessionID(key), Holder.INSTANCE.getCache());
                    String randomStr = getRandomString();
                    map.put(randomStr, policy);
                    cache.put(key, map);
                } catch (SessionWatchingNotSupported | SessionException e) {
                    return "";
                }
            }
        }
        return "";
    }

    /**
     * Returns cached policy object
     *
     * @param token single sign on token
     * @param cacheID Key for retrieve this policy
     * @return policy Policy object.
     * @throws AMConsoleException if policy object cannot be located.
     */
    public CachedPolicy getPolicy(SSOToken token, String cacheID) throws AMConsoleException {
        String key = token.getTokenID().toString();
        Map<String, CachedPolicy> map = cache.getUnchecked(key);
        CachedPolicy policy = map.get(cacheID);
        if (policy == null) {
            throw new AMConsoleException("Cannot locate cached policy " + cacheID);
        }
        return policy;
    }

    @Override
    public void sessionDeleted(String sessionId) {
        clearAllPolicies(sessionId);
    }

    @Override
    public void connectionLost() {
        clearAllPolicies();
    }

    @Override
    public void initiationFailed() {
        clearAllPolicies();
    }

    /**
     * Clears all cached policy of a given single sign on token ID
     *
     * @param tokenID single sign on token ID
     */
    private void clearAllPolicies(String tokenID) {
        synchronized(cache) {
            cache.invalidate(tokenID);
        }
        if (AMModelBase.debug.messageEnabled()) {
            AMModelBase.debug.warning("PolicyCache.clearAllPolicies," + tokenID);
        }
    }

    /**
     * Clears all cached policies for all token Ids in the cache.
     */
    private void clearAllPolicies() {
        for (String sessionId : cache.asMap().keySet()) {
            clearAllPolicies(sessionId);
        }
    }

    /**
     * Gets a random string
     *
     * @return random string
     */
    private String getRandomString() {
        StringBuilder sb = new StringBuilder(30);
        byte[] keyRandom = new byte[5];
        random.nextBytes(keyRandom);
        sb.append(currentTimeMillis());
        sb.append(Base64.encode(keyRandom));
        return (sb.toString());
    }
}
