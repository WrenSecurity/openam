/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2005 Sun Microsystems Inc. All Rights Reserved
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
 * $Id: AMSSOTokenListener.java,v 1.3 2008/06/25 05:41:22 qcheng Exp $
 *
 * Portions Copyrighted 2016 ForgeRock AS.
 */

package com.iplanet.am.sdk;

import java.util.Set;

import com.iplanet.dpro.session.watchers.listeners.SessionDeletionListener;
import com.iplanet.sso.SSOException;
import com.iplanet.sso.SSOToken;
import com.iplanet.sso.SSOTokenManager;

/**
 * This class implements the {@code SessionDeletionListener} interface.
 * <p>
 * This listener updates the profile name table, the objImplListeners table and AMCommonUtils's
 * dpCache by invalidating (removing) all entries affected because of the SSOToken becoming invalid.
 *
 * @deprecated  As of Sun Java System Access Manager 7.1.
 */
class AMSSOTokenListener implements SessionDeletionListener {

    @Override
    public void sessionDeleted(String sessionId) {
        SSOToken ssoToken;
        try {
            ssoToken = SSOTokenManager.getInstance().createSSOToken(sessionId);
        } catch (SSOException ignored) {
            // Failed to create token so no way for us to remove from cache.
            return;
        }
        // Remove the entries for the SSOToken to which this listener
        // corresponds to from the ProfileNameTable of AMObjectImpl class
        Set dnSet = AMObjectImpl.removeFromProfileNameTable(ssoToken);
        if (dnSet != null) {
            AMCommonUtils.debug.message("In AMSSOTokenListener.ssoTokenChanged(): dnSet NOT null!");
            // Also update the AMObjectImpl's objImplListener table
            AMObjectImpl.removeObjImplListeners(dnSet, ssoToken.getTokenID());
        }
    }

    @Override
    public void connectionLost() {
        for (String sessionId : AMObjectImpl.ProfileNameTable.INSTANCE.keys()) {
            sessionDeleted(sessionId);
        }
    }

    @Override
    public void initiationFailed() {
        for (String sessionId : AMObjectImpl.ProfileNameTable.INSTANCE.keys()) {
            sessionDeleted(sessionId);
        }
    }
}
