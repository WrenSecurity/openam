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
 * $Id: PageTrailManager.java,v 1.3 2008/07/10 23:27:22 veiming Exp $
 *
 * Portions Copyrighted 2011-2016 ForgeRock AS.
 */

package com.sun.identity.console.base;

import static org.forgerock.openam.utils.Time.currentTimeMillis;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import com.iplanet.dpro.session.SessionException;
import com.iplanet.dpro.session.SessionID;
import com.iplanet.dpro.session.service.SessionService;
import com.iplanet.dpro.session.watchers.listeners.SessionDeletionListener;
import com.iplanet.sso.SSOToken;
import com.sun.identity.console.base.model.AMAdminConstants;
import com.sun.identity.shared.debug.Debug;
import com.sun.identity.shared.encode.Base64;
import org.forgerock.guava.common.cache.Cache;
import org.forgerock.guava.common.cache.CacheBuilder;
import org.forgerock.guava.common.cache.CacheLoader;
import org.forgerock.guava.common.cache.LoadingCache;
import org.forgerock.guice.core.InjectorHolder;
import org.forgerock.openam.cts.continuous.watching.SessionWatchingNotSupported;

/**
 * This singleton class governs the tracking of page trail per user session.
 */
public class PageTrailManager {

    private static Debug debug = Debug.getInstance(AMAdminConstants.CONSOLE_DEBUG_FILENAME);

    private enum Holder {
        INSTANCE;

        private final PageTrailManager pageTrailManager = new PageTrailManager();
        private final SessionService sessionService;
        private final LoadingCache<String, Map<String, PageTrail>> cache;
        private final CacheInvalidationListener listener = new CacheInvalidationListener();

        Holder() {
            sessionService = InjectorHolder.getInstance(SessionService.class);
            cache = CacheBuilder.newBuilder()
                    .maximumSize(100)
                    .build(new PageTrailCacheLoader(sessionService, listener));
            try {
                sessionService.registerListener(listener);
            } catch (SessionWatchingNotSupported e) {
                debug.message("SessionStore.getSessionStore: {}", e.getMessage());
            }
        }
    }

    /**
     * The generated random string is used to cache page trail object when
     * we switch from on tab to another. Since it is used from caching
     * purposes, usage of secure random is not required.
     */
    private static Random random = new Random();

    private PageTrailManager() {
    }

    public static PageTrailManager getInstance() {
        return Holder.INSTANCE.pageTrailManager;
    }

    /**
     * Registers a page trail.
     *
     * @param token single sign on token
     * @param pageTrail Page Trail.
     * @return an unique key for retrieve this object in future
     */
    public String registerTrail(SSOToken token, PageTrail pageTrail) {
        String randomStr;
        String key = token.getTokenID().toString();

        synchronized (Holder.INSTANCE) {
            Map<String, PageTrail> entry = Holder.INSTANCE.cache.getUnchecked(key);
            randomStr = getRandomString();
            entry.put(randomStr, pageTrail);
        }
        return randomStr;
    }

    /**
     * Returns cached page trail.
     *
     * @param token single sign on token
     * @param cacheID Key for retrieve this page trail
     * @return page trail object if it is found. otherwises, return null
     */
    public PageTrail getTrail(SSOToken token, String cacheID) {
        Map<String, PageTrail> entry = Holder.INSTANCE.cache.getUnchecked(token.getTokenID().toString());
        if (!entry.isEmpty()) {
            return entry.get(cacheID);
        } else {
            return null;
        }
    }

    /**
     * Returns random string.
     *
     * @return random string.
     */
    private static String getRandomString() {
        StringBuilder sb = new StringBuilder(30);
        byte[] keyRandom = new byte[5];
        random.nextBytes(keyRandom);
        sb.append(currentTimeMillis());
        sb.append(Base64.encode(keyRandom));
        return (sb.toString());
    }

    private static final class PageTrailCacheLoader extends CacheLoader<String, Map<String, PageTrail>> {

        private final SessionService sessionService;
        private final SessionDeletionListener listener;

        private PageTrailCacheLoader(SessionService sessionService, SessionDeletionListener listener) {
            this.sessionService = sessionService;
            this.listener = listener;
        }

        @Override
        public Map<String, PageTrail> load(String ssoTokenId) throws Exception {
            Map<String, PageTrail> store = new HashMap<>(10);
            try {
                sessionService.notifyListenerFor(new SessionID(ssoTokenId), listener);
            } catch (SessionException | SessionWatchingNotSupported e) {
                debug.message("PageTrailManager.registerTrail: {}", e.getMessage());
            }
            return store;
        }
    }

    private static final class CacheInvalidationListener implements SessionDeletionListener {

        @Override
        public void sessionDeleted(String sessionId) {
            Holder.INSTANCE.cache.invalidate(sessionId);
        }

        @Override
        public void connectionLost() {
            // We rely on the cache to remove sessions when the connection is down
        }

        @Override
        public void initiationFailed() {
            // We rely on the cache to remove sessions when the connection is down
        }
    }
}
