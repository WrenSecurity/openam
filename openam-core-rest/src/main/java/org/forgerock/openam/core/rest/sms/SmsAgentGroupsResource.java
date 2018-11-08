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

import static org.forgerock.api.enums.CreateMode.ID_FROM_CLIENT;
import static org.forgerock.api.enums.ParameterSource.PATH;
import static org.forgerock.api.models.Action.action;
import static org.forgerock.api.models.Create.create;
import static org.forgerock.api.models.Delete.delete;
import static org.forgerock.api.models.Items.items;
import static org.forgerock.api.models.Parameter.parameter;
import static org.forgerock.api.models.Read.read;
import static org.forgerock.api.models.Update.update;
import static org.forgerock.json.JsonValue.json;
import static org.forgerock.json.JsonValue.object;
import static org.forgerock.json.resource.Responses.newQueryResponse;
import static org.forgerock.json.resource.Responses.newResourceResponse;
import static org.forgerock.openam.core.rest.sms.SmsResourceProvider.SCHEMA_DESCRIPTION;
import static org.forgerock.openam.core.rest.sms.SmsResourceProvider.TEMPLATE_DESCRIPTION;
import static org.forgerock.openam.i18n.apidescriptor.ApiDescriptorConstants.QUERY_DESCRIPTION;
import static org.forgerock.openam.i18n.apidescriptor.ApiDescriptorConstants.SMS_AGENT_GROUPS_RESOURCE;

import javax.inject.Named;
import java.net.MalformedURLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.iplanet.am.util.SystemProperties;
import com.iplanet.sso.SSOException;
import com.iplanet.sso.SSOToken;
import com.sun.identity.common.configuration.AgentConfiguration;
import com.sun.identity.common.configuration.ConfigurationException;
import com.sun.identity.idm.AMIdentity;
import com.sun.identity.idm.IdRepoException;
import com.sun.identity.idm.IdType;
import com.sun.identity.shared.debug.Debug;
import com.sun.identity.shared.locale.AMResourceBundleCache;
import com.sun.identity.sm.SMSException;
import com.sun.identity.sm.SchemaType;
import com.sun.identity.sm.ServiceSchema;
import com.sun.identity.sm.ServiceSchemaManager;
import org.forgerock.api.annotations.Action;
import org.forgerock.api.annotations.CollectionProvider;
import org.forgerock.api.annotations.Create;
import org.forgerock.api.annotations.Delete;
import org.forgerock.api.annotations.Handler;
import org.forgerock.api.annotations.Operation;
import org.forgerock.api.annotations.Query;
import org.forgerock.api.annotations.Read;
import org.forgerock.api.annotations.Schema;
import org.forgerock.api.annotations.Update;
import org.forgerock.api.enums.QueryType;
import org.forgerock.api.models.ApiDescription;
import org.forgerock.api.models.Paths;
import org.forgerock.api.models.Resource;
import org.forgerock.api.models.VersionedPath;
import org.forgerock.guava.common.base.Optional;
import org.forgerock.http.ApiProducer;
import org.forgerock.json.JsonValue;
import org.forgerock.json.resource.ActionResponse;
import org.forgerock.json.resource.BadRequestException;
import org.forgerock.json.resource.CreateRequest;
import org.forgerock.json.resource.InternalServerErrorException;
import org.forgerock.json.resource.NotFoundException;
import org.forgerock.json.resource.QueryRequest;
import org.forgerock.json.resource.QueryResourceHandler;
import org.forgerock.json.resource.QueryResponse;
import org.forgerock.json.resource.Request;
import org.forgerock.json.resource.ResourceException;
import org.forgerock.json.resource.ResourceResponse;
import org.forgerock.json.resource.UpdateRequest;
import org.forgerock.openam.core.realms.Realm;
import org.forgerock.openam.core.rest.sms.SmsAgentGroupsEndpointFunctions.AgentGroupsQueryFunction;
import org.forgerock.openam.rest.RealmContext;
import org.forgerock.openam.rest.resource.SSOTokenContext;
import org.forgerock.openam.utils.StringUtils;
import org.forgerock.services.context.Context;
import org.forgerock.services.descriptor.Describable;
import org.forgerock.util.i18n.LocalizableString;
import org.forgerock.util.promise.Promise;

/**
 * A collection resource for managing agent groups on the {@literal realm-config/agents/groups} resource.
 *
 * @since 14.0.0
 */
@CollectionProvider(details = @Handler(mvccSupported = false,
        resourceSchema = @Schema(fromType = String.class)))
public class SmsAgentGroupsResource implements Describable<ApiDescription, Request> {

    private final Debug debug;
    private final AMResourceBundleCache resourceBundleCache;
    private final Locale defaultLocale;
    private final AgentGroupsQueryFunction agentGroupsQuery;
    private final SmsAgentGroupsEndpointFunctions endpoint;
    private final String agentType;
    private final ServiceSchema schema;
    private final SmsJsonConverter converter;
    private final SmsResourceProvider resourceProvider;
    private final ApiDescription description;

    public SmsAgentGroupsResource(@Named("frRest") Debug debug,
            @Named("AMResourceBundleCache") AMResourceBundleCache resourceBundleCache,
            @Named("DefaultLocale") Locale defaultLocale, AgentGroupsQueryFunction agentGroupsQuery,
            SmsAgentGroupsEndpointFunctions endpoint, String agentType) throws SMSException, IdRepoException,
            SSOException {
        this.debug = debug;
        this.resourceBundleCache = resourceBundleCache;
        this.defaultLocale = defaultLocale;
        this.agentGroupsQuery = agentGroupsQuery;
        this.endpoint = endpoint;
        this.agentType = agentType;
        this.schema = endpoint.getAgentSchema(agentType);
        this.converter = endpoint.getJsonConverter(schema);
        this.resourceProvider = getResourceProvider(schema);
        this.description = ApiDescription.apiDescription().id("fake").version("v")
                .paths(Paths.paths().put("", VersionedPath.versionedPath()
                        .put(VersionedPath.UNVERSIONED, Resource.resource()
                                .title(new LocalizableString(SMS_AGENT_GROUPS_RESOURCE + agentType + ".title", this.getClass()))
                                .description(new LocalizableString(SMS_AGENT_GROUPS_RESOURCE + "description", this.getClass()))
                                .mvccSupported(false)
                                .items(items()
                                        .pathParameter(parameter().name("id").type("string").source(PATH).build())
                                        .read(read().build())
                                        .update(update().build())
                                        .delete(delete().build())
                                        .create(create().mode(ID_FROM_CLIENT).build())
                                        .build())
                                .resourceSchema(org.forgerock.api.models.Schema.schema()
                                        .schema(resourceProvider.createSchema(Optional.<Context>absent())).build())
                                .query(org.forgerock.api.models.Query.query().type(QueryType.FILTER)
                                        .description(SMS_AGENT_GROUPS_RESOURCE + QUERY_DESCRIPTION).queryableFields().build())
                                .action(action().name("schema").description(SCHEMA_DESCRIPTION).build())
                                .action(action().name("template").description(TEMPLATE_DESCRIPTION).build())
                                .build()).build()
                ).build()).build();
    }

    @Create(operationDescription = @Operation)
    public Promise<ResourceResponse, ResourceException> createInstance(Context context, CreateRequest request) {
        Realm realm = context.asContext(RealmContext.class).getRealm();
        SSOToken callerToken = context.asContext(SSOTokenContext.class).getCallerSSOToken();

        JsonValue payload = request.getContent();
        String agentName = request.getNewResourceId();
        if (StringUtils.isEmpty(agentName)) {
            return new BadRequestException("Create via POST not supported").asPromise();
        }

        String serverURL = payload.get("serverUrl").asString();
        payload.remove("serverUrl");

        try {
            Map<String, Set<String>> content = converter.fromJson(payload);
            HashMap<String, Set<String>> attributes = new HashMap<>(
                    AgentConfiguration.getDefaultValues(agentType, true));
            attributes.putAll(content);

            if (agentType.equals(AgentConfiguration.AGENT_TYPE_J2EE)
                    || agentType.equals(AgentConfiguration.AGENT_TYPE_WEB)) {
                if (StringUtils.isEmpty(serverURL)) {
                    return new BadRequestException("Missing required 'serverUrl' attribute in request body")
                            .asPromise();
                }
                attributes.put("AgentType", new HashSet<>(Collections.singleton(agentType)));
                AgentConfiguration.createAgentGroup(callerToken, realm.asPath(),
                        agentName, agentType, attributes,
                        serverURL, null);
            } else {
                AgentConfiguration.createAgentGroup(callerToken, realm.asPath(),
                        agentName, agentType, attributes);
            }

            return readInstance(context, agentName);
        } catch (BadRequestException e) {
            return e.asPromise();
        } catch (IdRepoException e) {
            return endpoint.handleIdRepoException(e).asPromise();
        } catch (SSOException e) {
            debug.warning("::SmsAgentsGroupsResource:: SSOException on create", e);
            return new InternalServerErrorException("Unable to create SMS config: " + e.getMessage()).asPromise();
        } catch (SMSException e) {
            debug.warning("::SmsAgentsGroupsResource:: SMSException on create", e);
            return new InternalServerErrorException("Unable to create SMS config: " + e.getMessage()).asPromise();
        } catch (ConfigurationException | MalformedURLException e) {
            debug.warning("::SmsAgentsGroupsResource:: ConfigurationException on create", e);
            return new InternalServerErrorException("Unable to create SMS config: " + e.getMessage()).asPromise();
        }
    }

    @Read(operationDescription = @Operation)
    public Promise<ResourceResponse, ResourceException> readInstance(Context context, String resourceId) {
        Realm realm = context.asContext(RealmContext.class).getRealm();
        SSOToken callerToken = context.asContext(SSOTokenContext.class).getCallerSSOToken();
        AMIdentity agentIdentity = getAgentIdentity(callerToken, resourceId, realm);
        try {
            if (!agentIdentity.isExists() || !getAgentType(agentIdentity).equals(agentType)) {
                return new NotFoundException().asPromise();
            }
            JsonValue result = converter.toJson(realm.asPath(), agentIdentity.getAttributes(),
                    false, json(object()));
            return newResourceResponse(resourceId, String.valueOf(result.getObject().hashCode()), result).asPromise();
        } catch (IdRepoException e) {
            return endpoint.handleIdRepoException(e).asPromise();
        } catch (SSOException e) {
            debug.warning("::SmsAgentsGroupsResource:: SSOException on read", e);
            return new InternalServerErrorException("Unable to read SMS config: " + e.getMessage()).asPromise();
        }
    }

    @Update(operationDescription = @Operation)
    public Promise<ResourceResponse, ResourceException> updateInstance(Context context, String resourceId,
            UpdateRequest request) {
        Realm realm = context.asContext(RealmContext.class).getRealm();
        SSOToken callerToken = context.asContext(SSOTokenContext.class).getCallerSSOToken();
        AMIdentity agentIdentity = getAgentIdentity(callerToken, resourceId, realm);

        JsonValue payload = request.getContent();
        payload.remove("serverUrl");
        try {
            if (!agentIdentity.isExists() || !getAgentType(agentIdentity).equals(agentType)) {
                return new NotFoundException().asPromise();
            }
            Map<String, Set<String>> content = converter.fromJson(payload);
            agentIdentity.setAttributes(content);
            agentIdentity.store();
            JsonValue result = converter.toJson(realm.asPath(), agentIdentity.getAttributes(), false,
                    json(object()));
            return newResourceResponse(resourceId, String.valueOf(result.getObject().hashCode()), result).asPromise();
        } catch (BadRequestException e) {
            return e.asPromise();
        } catch (IdRepoException e) {
            return endpoint.handleIdRepoException(e).asPromise();
        } catch (SSOException e) {
            debug.warning("::SmsAgentsGroupsResource:: SSOException on update", e);
            return new InternalServerErrorException("Unable to update SMS config: " + e.getMessage()).asPromise();
        }
    }

    @Delete(operationDescription = @Operation)
    public Promise<ResourceResponse, ResourceException> deleteInstance(Context context, String resourceId) {
        Realm realm = context.asContext(RealmContext.class).getRealm();
        SSOToken callerToken = context.asContext(SSOTokenContext.class).getCallerSSOToken();
        AMIdentity agentIdentity = getAgentIdentity(callerToken, resourceId, realm);
        try {
            if (!agentIdentity.isExists() || !getAgentType(agentIdentity).equals(agentType)) {
                return new NotFoundException().asPromise();
            }
            Promise<ResourceResponse, ResourceException> readResponse = readInstance(context, resourceId);
            AgentConfiguration.deleteAgentGroups(callerToken, realm.asPath(),
                    Collections.singleton(agentIdentity));
            return readResponse;
        } catch (IdRepoException e) {
            return endpoint.handleIdRepoException(e).asPromise();
        } catch (SSOException e) {
            debug.warning("::SmsAgentsGroupsResource:: SSOException on delete", e);
            return new InternalServerErrorException("Unable to delete SMS config: " + e.getMessage()).asPromise();
        } catch (SMSException e) {
            debug.warning("::SmsAgentsGroupsResource:: SMSException on delete", e);
            return new InternalServerErrorException("Unable to delete SMS config: " + e.getMessage()).asPromise();
        }
    }

    @Action(operationDescription = @Operation)
    public Promise<ActionResponse, ResourceException> template(Context context) {
        return resourceProvider.template();
    }

    @Action(operationDescription = @Operation)
    public Promise<ActionResponse, ResourceException> schema(Context context) {
        return resourceProvider.schema(context);
    }

    @Query(operationDescription = @Operation, type = QueryType.FILTER, queryableFields = "*")
    public Promise<QueryResponse, ResourceException> query(Context context, QueryRequest request,
            QueryResourceHandler handler) {
        Realm realm = context.asContext(RealmContext.class).getRealm();
        SSOToken callerToken = context.asContext(SSOTokenContext.class).getCallerSSOToken();
        try {
            Map<String, Map<String, Set<String>>> results = agentGroupsQuery.apply(realm, callerToken);
            for (Map.Entry<String, Map<String, Set<String>>> result : results.entrySet()) {
                String agentTypeFromAttributes = endpoint.getAgentType(result.getValue());
                if (agentTypeFromAttributes.equals(agentType)) {
                    JsonValue agentJson = converter.toJson(realm.asPath(), result.getValue(), false, json(object()));
                    handler.handleResource(newResourceResponse(result.getKey(),
                            String.valueOf(agentJson.getObject().hashCode()), agentJson));
                }
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

    private SmsResourceProvider getResourceProvider(ServiceSchema schema) {
        return new AgentsGroupsResourceActionProvider(schema, schema.getServiceType(),
                Collections.<ServiceSchema>emptyList(), null, false, converter,
                debug, resourceBundleCache, defaultLocale, description);
    }

    private AMIdentity getAgentIdentity(SSOToken ssoToken, String agentName, Realm realm) {
        return new AMIdentity(ssoToken, agentName, IdType.AGENTGROUP, realm.asPath(), null);
    }

    private String getAgentType(AMIdentity agentIdentity) throws IdRepoException, SSOException {
        return endpoint.getAgentType(agentIdentity.getAttributes());
    }

    @Override
    public ApiDescription api(ApiProducer<ApiDescription> producer) {
        return description;
    }

    @Override
    public ApiDescription handleApiRequest(Context context, Request request) {
        return description;
    }

    @Override
    public void addDescriptorListener(Listener listener) {
    }

    @Override
    public void removeDescriptorListener(Listener listener) {
    }

    private static final class AgentsGroupsResourceActionProvider extends SmsResourceProvider {

        private final ApiDescription description;

        AgentsGroupsResourceActionProvider(ServiceSchema schema, SchemaType type, List<ServiceSchema> subSchemaPath,
                String uriPath, boolean serviceHasInstanceName, SmsJsonConverter converter, Debug debug,
                AMResourceBundleCache resourceBundleCache, Locale defaultLocale, ApiDescription description) {
            super(schema, type, subSchemaPath, uriPath, serviceHasInstanceName, converter, debug, resourceBundleCache,
                    defaultLocale);
            this.description = description;
        }

        @Override
        public ApiDescription api(ApiProducer<ApiDescription> producer) {
            return description;
        }

        @Override
        protected JsonValue createTemplate() {
            Map<String, String> map = new HashMap<>(5);
            map.put("SERVER_PROTO", SystemProperties.get("com.iplanet.am.server.protocol"));
            map.put("SERVER_HOST", SystemProperties.get("com.iplanet.am.server.host"));
            map.put("SERVER_PORT", SystemProperties.get("com.iplanet.am.server.port"));
            map.put("AM_SERVICES_DEPLOY_URI", SystemProperties.get("com.iplanet.am.services.deploymentDescriptor"));
            map.put("REALM", "");
            Map<String, Set<String>> attributeDefaults = new HashMap<>(schema.getAttributeDefaults());
            tagswapAttributeValues(attributeDefaults, map);

            return converter.toJson(attributeDefaults, false);
        }

        private void tagswapAttributeValues(Map<String, Set<String>> attributeValues, Map<String, String> tagswapInfo) {
            for (String attrName : attributeValues.keySet()) {
                Set<String> values = attributeValues.get(attrName);
                Set<String> newValues = new HashSet<>(values.size());
                for (String value : values) {
                    newValues.add(tagswap(tagswapInfo, value));
                }
                values.clear();
                values.addAll(newValues);
            }
        }

        private String tagswap(Map<String, String> map, String value) {
            if (value == null || value.isEmpty()) {
                return value;
            }

            for (String k : map.keySet()) {
                value = value.replaceAll("@" + k + "@", map.get(k));
            }
            return value;
        }

        @Override
        public ApiDescription handleApiRequest(Context context, Request request) {
            return description;
        }

        @Override
        public void addDescriptorListener(Listener listener) {
        }

        @Override
        public void removeDescriptorListener(Listener listener) {
        }
    }
}
