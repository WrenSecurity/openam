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

import static org.forgerock.json.JsonValue.json;
import static org.forgerock.json.JsonValue.object;
import static org.forgerock.json.resource.Responses.newQueryResponse;
import static org.forgerock.json.resource.Responses.newResourceResponse;
import static org.forgerock.openam.i18n.apidescriptor.ApiDescriptorConstants.SMS_AGGREGATING_AGENT_GROUPS_QUERY_HANDLER;

import javax.inject.Named;
import java.util.Map;
import java.util.Set;

import com.iplanet.sso.SSOException;
import com.iplanet.sso.SSOToken;
import com.sun.identity.idm.IdRepoException;
import com.sun.identity.shared.debug.Debug;
import com.sun.identity.sm.SMSException;
import org.forgerock.api.annotations.Handler;
import org.forgerock.api.annotations.Operation;
import org.forgerock.api.annotations.Query;
import org.forgerock.api.annotations.RequestHandler;
import org.forgerock.api.annotations.Schema;
import org.forgerock.api.enums.QueryType;
import org.forgerock.json.JsonValue;
import org.forgerock.json.resource.InternalServerErrorException;
import org.forgerock.json.resource.QueryRequest;
import org.forgerock.json.resource.QueryResourceHandler;
import org.forgerock.json.resource.QueryResponse;
import org.forgerock.json.resource.ResourceException;
import org.forgerock.openam.core.realms.Realm;
import org.forgerock.openam.core.rest.sms.SmsAgentGroupsEndpointFunctions.AgentGroupsQueryFunction;
import org.forgerock.openam.rest.RealmContext;
import org.forgerock.openam.rest.resource.SSOTokenContext;
import org.forgerock.services.context.Context;
import org.forgerock.util.promise.Promise;

/**
 * A handler for handling queries on the {@literal realm-config/agents/groups} resource.
 *
 * @since 14.0.0
 */
@RequestHandler(@Handler(mvccSupported = false,
        title = SMS_AGGREGATING_AGENT_GROUPS_QUERY_HANDLER + "title",
        description = SMS_AGGREGATING_AGENT_GROUPS_QUERY_HANDLER + "description",
        resourceSchema = @Schema(schemaResource = "SmsAggregatingAgentGroupsQueryHandler.schema.json")))
public class SmsAggregatingAgentGroupsQueryHandler {

    private final Debug debug;
    private final AgentGroupsQueryFunction agentGroupsQuery;
    private final SmsAgentGroupsEndpointFunctions endpoint;

    SmsAggregatingAgentGroupsQueryHandler(@Named("frRest") Debug debug,
            AgentGroupsQueryFunction agentGroupsQuery, SmsAgentGroupsEndpointFunctions endpoint) {
        this.debug = debug;
        this.agentGroupsQuery = agentGroupsQuery;
        this.endpoint = endpoint;
    }

    @Query(operationDescription = @Operation(description = SMS_AGGREGATING_AGENT_GROUPS_QUERY_HANDLER
            + "query.description"),
            type = QueryType.FILTER, queryableFields = "*")
    public Promise<QueryResponse, ResourceException> handleQuery(Context context, QueryRequest request,
            QueryResourceHandler handler) throws InternalServerErrorException {
        Realm realm = context.asContext(RealmContext.class).getRealm();
        SSOToken callerToken = context.asContext(SSOTokenContext.class).getCallerSSOToken();

        try {
            Map<String, Map<String, Set<String>>> results = agentGroupsQuery.apply(realm, callerToken);
            for (Map.Entry<String, Map<String, Set<String>>> result : results.entrySet()) {
                String agentType = endpoint.getAgentType(result.getValue());
                JsonValue agentJson = endpoint.getJsonConverter(endpoint.getAgentSchema(agentType))
                        .toJson(realm.asPath(), result.getValue(), false, json(object()));
                handler.handleResource(newResourceResponse(result.getKey(),
                        String.valueOf(agentJson.getObject().hashCode()), agentJson));
            }
            return newQueryResponse().asPromise();
        } catch (IdRepoException e) {
            return endpoint.handleIdRepoException(e).asPromise();
        } catch (SSOException e) {
            debug.warning("::SmsAgentsGroupsResource:: SSOException on query", e);
            return new InternalServerErrorException("Unable to query SMS config: " + e.getMessage()).asPromise();
        } catch (SMSException e) {
            debug.warning("::SmsAgentsGroupsResource:: SMSException on query", e);
            return new InternalServerErrorException("Unable to query SMS config: " + e.getMessage()).asPromise();
        }
    }
}
