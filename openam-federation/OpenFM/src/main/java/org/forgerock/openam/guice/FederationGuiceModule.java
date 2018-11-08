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
package org.forgerock.openam.guice;

import java.util.concurrent.ScheduledExecutorService;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.forgerock.openam.audit.context.AMExecutorServiceFactory;

import com.google.inject.PrivateModule;
import com.google.inject.Provides;
import com.google.inject.name.Names;

/**
 * Responsible for declaring the bindings required for the federation code base.
 */
public class FederationGuiceModule extends PrivateModule {

    /**
     * Tag for all operations for use within the federation session management code. e.g. Thread names, Debugger etc.
     */
    public static final String FEDERATION_SESSION_MANAGEMENT = "FederationSessionManagement";

    @Override
    protected void configure() {
        expose(ScheduledExecutorService.class).annotatedWith(Names.named(FEDERATION_SESSION_MANAGEMENT));
    }

    @Provides
    @Singleton
    @Inject
    @Named(FEDERATION_SESSION_MANAGEMENT)
    public ScheduledExecutorService getFederationScheduledService(AMExecutorServiceFactory esf) {
        return esf.createScheduledService(1, FEDERATION_SESSION_MANAGEMENT);
    }
}
