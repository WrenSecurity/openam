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

package org.forgerock.openam.core.rest.sms;

import javax.inject.Named;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.iplanet.sso.SSOException;
import com.iplanet.sso.SSOToken;
import com.sun.identity.idm.IdRepoErrorCode;
import com.sun.identity.idm.IdRepoException;
import com.sun.identity.idm.IdType;
import com.sun.identity.idm.RepoSearchResults;
import com.sun.identity.idm.plugins.internal.AgentsRepo;
import com.sun.identity.shared.debug.Debug;
import com.sun.identity.sm.SMSException;
import com.sun.identity.sm.ServiceSchema;
import com.sun.identity.sm.ServiceSchemaManager;
import org.forgerock.json.resource.InternalServerErrorException;
import org.forgerock.json.resource.NotFoundException;
import org.forgerock.json.resource.PreconditionFailedException;
import org.forgerock.json.resource.ResourceException;
import org.forgerock.openam.core.realms.Realm;
import org.forgerock.openam.utils.CollectionUtils;
import org.forgerock.openam.utils.CrestQuery;

/**
 * Functions used by the agent groups REST SMS endpoints.
 *
 * @since 13.0.0
 */
final class SmsAgentGroupsEndpointFunctions {

    private final Debug debug;
    private final ServiceSchemaManager serviceSchemaManager;

    SmsAgentGroupsEndpointFunctions(Debug debug, ServiceSchemaManager serviceSchemaManager) {
        this.debug = debug;
        this.serviceSchemaManager = serviceSchemaManager;
    }

    String getAgentType(Map<String, Set<String>> agentAttributes) {
        return CollectionUtils.getFirstItem(agentAttributes.get("AgentType"));
    }

    ServiceSchema getAgentSchema(String agentType) throws SMSException, IdRepoException, SSOException {
        return serviceSchemaManager.getOrganizationSchema().getSubSchema(agentType);
    }

    SmsJsonConverter getJsonConverter(ServiceSchema serviceSchema) {
        return new SmsJsonConverter(serviceSchema);
    }

    ResourceException handleIdRepoException(IdRepoException e) {
        switch (e.getErrorCode()) {
            case IdRepoErrorCode.TYPE_NOT_FOUND: {
                debug.warning("::SmsAgentsGroupsResource:: IdRepoException TYPE_NOT_FOUND on access", e);
                return new NotFoundException("Unable to access SMS config: " + e.getMessage());
            }
            case IdRepoErrorCode.IDENTITY_OF_TYPE_ALREADY_EXISTS: {
                debug.warning("::SmsAgentsGroupsResource:: IdRepoException IDENTITY_OF_TYPE_ALREADY_EXISTS on access", e);
                return new PreconditionFailedException("Unable to access SMS config: " + e.getMessage());
            }
            default: {
                debug.warning("::SmsAgentsGroupsResource:: SSOException on read", e);
                return new InternalServerErrorException("Unable to access SMS config: " + e.getMessage());
            }
        }
    }

    /**
     * Function for querying agent groups.
     */
    static final class AgentGroupsQueryFunction {

        Map<String, Map<String, Set<String>>> apply(Realm realm, SSOToken ssoToken)
                throws IdRepoException, SSOException, SMSException {
            RepoSearchResults results = getAgentsRepo(realm).search(ssoToken, IdType.AGENTGROUP, new CrestQuery("*"),
                    0, 0, null,false, 0, null, false);
            return (Map<String, Map<String, Set<String>>>) results.getResultAttributes();
        }

        private AgentsRepo getAgentsRepo(Realm realm) throws IdRepoException { //TODO cache
            AgentsRepo agentsRepo = new AgentsRepo();
            Map<String, Set<String>> config = new HashMap<>(1);
            Set<String> realmName = new HashSet<>(1);
            realmName.add(realm.asDN());
            config.put("agentsRepoRealmName", realmName);
            agentsRepo.initialize(config);
            return agentsRepo;
        }
    }
}
