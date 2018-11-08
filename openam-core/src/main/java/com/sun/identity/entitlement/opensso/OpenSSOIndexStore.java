/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2009 Sun Microsystems Inc. All Rights Reserved
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
 * $Id: OpenSSOIndexStore.java,v 1.13 2010/01/25 23:48:15 veiming Exp $
 *
 * Portions copyright 2011-2016 ForgeRock AS.
 */

package com.sun.identity.entitlement.opensso;

import static java.util.Collections.singleton;
import static org.forgerock.openam.entitlement.PolicyConstants.SUPER_ADMIN_SUBJECT;
import static org.forgerock.openam.entitlement.utils.EntitlementUtils.getApplicationService;
import static org.forgerock.openam.entitlement.utils.EntitlementUtils.getEntitlementConfiguration;

import java.security.AccessController;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.security.auth.Subject;

import org.forgerock.openam.entitlement.PolicyConstants;
import org.forgerock.openam.ldap.LDAPUtils;
import org.forgerock.util.Reject;

import com.iplanet.sso.SSOException;
import com.iplanet.sso.SSOToken;
import com.sun.identity.entitlement.Application;
import com.sun.identity.entitlement.ApplicationTypeManager;
import com.sun.identity.entitlement.EntitlementConfiguration;
import com.sun.identity.entitlement.EntitlementException;
import com.sun.identity.entitlement.EntitlementThreadPool;
import com.sun.identity.entitlement.IPrivilege;
import com.sun.identity.entitlement.Privilege;
import com.sun.identity.entitlement.PrivilegeIndexStore;
import com.sun.identity.entitlement.ReferralPrivilege;
import com.sun.identity.entitlement.ResourceSearchIndexes;
import com.sun.identity.entitlement.SequentialThreadPool;
import com.sun.identity.entitlement.SubjectAttributesCollector;
import com.sun.identity.entitlement.SubjectAttributesManager;
import com.sun.identity.entitlement.interfaces.IThreadPool;
import com.sun.identity.entitlement.util.SearchFilter;
import com.sun.identity.entitlement.util.SimpleIterator;
import com.sun.identity.policy.PolicyConfig;
import com.sun.identity.policy.PolicyManager;
import com.sun.identity.security.AdminTokenAction;
import com.sun.identity.shared.BufferedIterator;
import com.sun.identity.sm.DNMapper;
import com.sun.identity.sm.OrganizationConfigManager;
import com.sun.identity.sm.SMSException;
import com.sun.identity.sm.ServiceConfigManager;
import com.sun.identity.sm.ServiceListener;
import com.sun.identity.sm.ServiceManager;
import com.sun.identity.sm.ServiceSchema;
import com.sun.identity.sm.ServiceSchemaManager;

public class OpenSSOIndexStore extends PrivilegeIndexStore {

    private static final int DEFAULT_THREAD_SIZE = 1;
    private static final DataStore dataStore = DataStore.getInstance();
    private static IThreadPool threadPool;
    private static boolean isMultiThreaded;
    private Subject superAdminSubject;

    // Initialize the caches
    static {
        Subject adminSubject = SubjectUtils.createSuperAdminSubject();
        EntitlementConfiguration ec = getEntitlementConfiguration(adminSubject, "/");

        int threadSize = getInteger(ec,
            EntitlementConfiguration.POLICY_SEARCH_THREAD_SIZE,
            DEFAULT_THREAD_SIZE);
        isMultiThreaded = (threadSize > 1);
        threadPool = (isMultiThreaded) ? new EntitlementThreadPool(
            threadSize) : new SequentialThreadPool();
        // Register listener for realm deletions
        try {
            SSOToken adminToken = AccessController.doPrivileged(
                AdminTokenAction.getInstance());
            ServiceConfigManager serviceConfigManager =
                new ServiceConfigManager(PolicyManager.POLICY_SERVICE_NAME,
                adminToken);
            serviceConfigManager.addListener(new EntitlementsListener());
        } catch (Exception e) {
            PolicyConstants.DEBUG.error("OpenSSOIndexStore.init " +
                "Unable to register for SMS notifications", e);
        }
    }

    private static int getInteger(EntitlementConfiguration ec, String key,
        int defaultVal) {
        Set<String> set = ec.getConfiguration(key);
        if ((set == null) || set.isEmpty()) {
            return defaultVal;
        }
        String str = set.iterator().next();
        return getNumeric(str, defaultVal);
    }

    // Instance variables
    private String realmDN;
    private EntitlementConfiguration entitlementConfig;

    /**
     * Constructor.
     *
     * @param realm Realm Name
     */
    public OpenSSOIndexStore(Subject adminSubject, String realm) {
        super(adminSubject, realm);
        superAdminSubject = SubjectUtils.createSuperAdminSubject();
        realmDN = DNMapper.orgNameToDN(realm);
        entitlementConfig = getEntitlementConfiguration(adminSubject, realm);
    }

    private static int getNumeric(String str, int defaultValue) {
        try {
            return Integer.parseInt(str);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Adds a set of privileges to the data store. Proper indexes will be
     * created to speed up policy evaluation.
     *
     * @param privileges Privileges to be added.
     * @throws com.sun.identity.entitlement.EntitlementException if addition
     * failed.
     */
    public void add(Set<IPrivilege> privileges)
        throws EntitlementException {

        for (IPrivilege p : privileges) {
            if (p instanceof Privilege) {
                add((Privilege)p);
            } else if (p instanceof ReferralPrivilege) {
                add((ReferralPrivilege)p);
            }
        }
    }

    private void add(Privilege privilege) throws EntitlementException {
        Subject adminSubject = getAdminSubject();
        String realm = getRealm();
        privilege.canonicalizeResources(adminSubject,
            DNMapper.orgNameToRealmName(realm));
        dataStore.add(adminSubject, realmDN, privilege);
        entitlementConfig.addSubjectAttributeNames(
            privilege.getEntitlement().getApplicationName(),
            SubjectAttributesManager.getRequiredAttributeNames(privilege));
    }

    private void add(ReferralPrivilege referral)
        throws EntitlementException {
        Subject adminSubject = getAdminSubject();
        String realm = getRealm();

        // clone so that canonicalized resource name will be localized.
        ReferralPrivilege clone = (ReferralPrivilege)referral.clone();
        clone.canonicalizeResources(adminSubject,
            DNMapper.orgNameToRealmName(realm));
        dataStore.addReferral(adminSubject, realm, clone);
    }

    /**
     * Deletes a set of privileges from data store.
     *
     * @param privilegeName Name of privilege to be deleted.
     * @throws EntitlementException if deletion
     * failed.
     */
    public void delete(String privilegeName)
        throws EntitlementException {
        delete(privilegeName, true);
    }

    /**
     * Deletes a referral privilege from data store.
     *
     * @param privilegeName Name of referral to be deleted.
     * @throws EntitlementException if deletion
     * failed.
     */
    public void deleteReferral(String privilegeName)
        throws EntitlementException {
        deleteReferral(privilegeName, true);
    }

    /**
     * Deletes a privilege from data store.
     *
     * @param privileges Privileges to be deleted.
     * @throws EntitlementException if deletion
     * failed.
     */
    public void delete(Set<IPrivilege> privileges)
        throws EntitlementException {

        for (IPrivilege p : privileges) {
            if (p instanceof Privilege) {
                delete(p.getName(), true);
            } else {
                deleteReferral(p.getName(), true);
            }
        }
    }

    public String delete(String privilegeName, boolean notify)
        throws EntitlementException {
        Subject adminSubject = getAdminSubject();
        String realm = getRealm();
        String dn = DataStore.getPrivilegeDistinguishedName(
            privilegeName, realm, null);
        if (notify) {
            dataStore.remove(adminSubject, realmDN, privilegeName);
        } else {
        }

        return dn;
    }


    public String deleteReferral(String privilegeName, boolean notify)
        throws EntitlementException {
        Subject adminSubject = getAdminSubject();
        String realm = getRealm();
        String dn = DataStore.getPrivilegeDistinguishedName(
            privilegeName, realm, DataStore.REFERRAL_STORE);
        if (notify) {
            dataStore.removeReferral(adminSubject, realm, privilegeName);
        }
        return dn;
    }

    /**
     * Search for policies.
     *
     * @param realm
     *         The realm of which the policy resides.
     * @param indexes
     *         Policy indexes.
     * @param subjectIndexes
     *         Subject indexes.
     * @param bSubTree
     *         Whether in subtree mode.
     * @return An iterator of policies.
     * @throws EntitlementException
     *         Should an error occur searching for policies.
     */
    public Iterator<IPrivilege> search(String realm,
            ResourceSearchIndexes indexes,
            Set<String> subjectIndexes,
            boolean bSubTree
    ) throws EntitlementException {
        BufferedIterator iterator = (isMultiThreaded) ? new BufferedIterator() : new SimpleIterator();

        // When not in subtree mode path indexes should be available.
        if (!bSubTree && indexes.getPathIndexes().isEmpty()) {
            return iterator;
        }

        // When in subtree mode parent path indexes should be available.
        if (bSubTree && indexes.getParentPathIndexes().isEmpty()) {
            return iterator;
        }

        threadPool.submit(new SearchTask(iterator, indexes, subjectIndexes, bSubTree));
        return iterator;
    }

    /**
     * Retrieve an individual privilege from the data store.
     *
     * @param privilegeName Name of the privilege to return.
     * @return The privilege, or empty if none was found.
     */
    public IPrivilege getPrivilege(String privilegeName) {
        try {
            return dataStore.getPrivilege(getRealm(), privilegeName);
        } catch (EntitlementException e) {
            PolicyConstants.DEBUG.error(
                    "OpenSSOIndexStore.GetTask.runPolicy", e);
        }

        return null;
    }

    /**
     * Returns a set of privilege names that satifies a search filter.
     *
     * @param filters Search filters.
     * @param boolAnd <code>true</code> to have filters as exclusive.
     * @param numOfEntries Number of max entries.
     * @param sortResults <code>true</code> to have result sorted.
     * @param ascendingOrder <code>true</code> to have result sorted in
     * ascending order.
     * @return a set of privilege names that satifies a search filter.
     * @throws EntitlementException if search failed.
     */
    public Set<String> searchPrivilegeNames(
        Set<SearchFilter> filters,
        boolean boolAnd,
        int numOfEntries,
        boolean sortResults,
        boolean ascendingOrder
    ) throws EntitlementException {
        Subject adminSubject = getAdminSubject();
        String realm = getRealm();

        String searchFilters = getSearchFilter(filters, boolAnd);

        return dataStore.search(adminSubject, realm, searchFilters,
                numOfEntries * (2), sortResults, ascendingOrder);
    }

    private String getSearchFilter(Set<SearchFilter> filters, boolean boolAnd) {
        StringBuilder strFilter = new StringBuilder();
        if ((filters == null) || filters.isEmpty()) {
            strFilter.append("(ou=*)");
        } else {
            if (filters.size() == 1) {
                strFilter.append(filters.iterator().next().getFilter());
            } else {
                if (boolAnd) {
                    strFilter.append("(&");
                } else {
                    strFilter.append("(|");
                }
                for (SearchFilter psf : filters) {
                    strFilter.append(psf.getFilter());
                }
                strFilter.append(")");
            }
        }

        return strFilter.toString();
    }


    /**
     * Returns a set of referral privilege names that satifies a search filter.
     *
     * @param filters Search filters.
     * @param boolAnd <code>true</code> to have filters as exclusive.
     * @param numOfEntries Number of max entries.
     * @param sortResults <code>true</code> to have result sorted.
     * @param ascendingOrder <code>true</code> to have result sorted in
     * ascending order.
     * @return a set of referral privilege names that satifies a search filter.
     * @throws EntitlementException if search failed.
     */
    public Set<String> searchReferralPrivilegeNames(
        Set<SearchFilter> filters,
        boolean boolAnd,
        int numOfEntries,
        boolean sortResults,
        boolean ascendingOrder
    ) throws EntitlementException {
        return searchReferralPrivilegeNames(filters, getAdminSubject(),
            getRealm(), boolAnd, numOfEntries, sortResults, ascendingOrder);
    }

    /**
     * Returns a set of referral privilege names that matched a set of search
     * criteria.
     *
     * @param filters Set of search filter (criteria).
     * @param boolAnd <code>true</code> to be inclusive.
     * @param numOfEntries Number of maximum search entries.
     * @param sortResults <code>true</code> to have the result sorted.
     * @param ascendingOrder  <code>true</code> to have the result sorted in
     *        ascending order.
     * @return a set of referral privilege names that matched a set of search
     *         criteria.
     * @throws EntitlementException if search failed.
     */
    public Set<String> searchReferralPrivilegeNames(
        Set<SearchFilter> filters,
        Subject adminSubject,
        String currentRealm,
        boolean boolAnd,
        int numOfEntries,
        boolean sortResults,
        boolean ascendingOrder
    ) throws EntitlementException {
        StringBuilder strFilter = new StringBuilder();
        if (filters.isEmpty()) {
            strFilter.append("(ou=*)");
        } else {
            if (filters.size() == 1) {
                strFilter.append(filters.iterator().next().getFilter());
            } else {
                if (boolAnd) {
                    strFilter.append("(&");
                } else {
                    strFilter.append("(|");
                }
                for (SearchFilter psf : filters) {
                    strFilter.append(psf.getFilter());
                }
                strFilter.append(")");
            }
        }
        return dataStore.searchReferral(adminSubject, currentRealm,
                strFilter.toString(), numOfEntries, sortResults, ascendingOrder);
    }

    /**
     * Returns a set of resources that are referred to this realm.
     *
     * @param applicationTypeName Application type name,
     * @return a set of resources that are referred to this realm.
     * @throws EntitlementException if resources cannot be returned.
     */
    @Override
    public Set<String> getReferredResources(String applicationTypeName)
        throws EntitlementException {
        String realm = getRealm();
        if (realm.equals("/")) {
            return Collections.EMPTY_SET;
        }
        
        if (LDAPUtils.isDN(realm)) {
            realm = DNMapper.orgNameToRealmName(realm);
        }

        SSOToken adminToken = SubjectUtils.getSSOToken(superAdminSubject);

        try {
            Set<String> results = new HashSet<String>();
            Set<String> realms = getPeerRealms(realm);
            realms.addAll(getParentRealms(realm));
            String filter = "(&(ou=" + DataStore.REFERRAL_APPLS + "="
                + applicationTypeName + ")(ou=" + DataStore.REFERRAL_REALMS +
                "=" + realm + "))";

            Map<String, Set<ReferralPrivilege>> referrals = new
                HashMap<String, Set<ReferralPrivilege>>();
            for (String rlm : realms) {
                referrals.put(rlm, dataStore.searchReferrals(
                    adminToken, rlm, filter));
            }

            for (String rlm : referrals.keySet()) {
                Set<ReferralPrivilege> rPrivileges = referrals.get(rlm);
                String realmName = LDAPUtils.isDN(rlm) ?
                    DNMapper.orgNameToRealmName(rlm) : rlm;
                for (ReferralPrivilege r : rPrivileges) {
                    Map<String, Set<String>> map =
                        r.getOriginalMapApplNameToResources();
                    for (String a : map.keySet()) {
                        Application appl = getApplicationService(SUPER_ADMIN_SUBJECT, realmName).getApplication(a);
                        if (appl.getApplicationType().getName().equals(
                            applicationTypeName)) {
                            results.addAll(map.get(a));
                        }
                    }
                }
            }

            results.addAll(getOrgAliasMappingResources(
                realm, applicationTypeName));

            return results;
        } catch (SMSException ex) {
            PolicyConstants.DEBUG.error(
                "OpenSSOIndexStore.getReferredResources", ex);
            Object[] param = {realm};
            throw new EntitlementException(275, param);
        }
    }

    @Override
    public List<Privilege> findAllPolicies() throws EntitlementException {
        return dataStore.findPoliciesByRealm(getRealm());
    }

    @Override
    public List<Privilege> findAllPoliciesByApplication(String application) throws EntitlementException {
        return dataStore.findPoliciesByRealmAndApplication(getRealm(), application);
    }

    @Override
    public List<Privilege> findAllPoliciesByIdentityUid(String uid) throws EntitlementException {
        Reject.ifNull(uid);
        Set<String> subjectIndexes = singleton(SubjectAttributesCollector.NAMESPACE_IDENTITY + "=" + uid);
        return dataStore.findAllPoliciesByRealmAndSubjectIndex(getRealm(), subjectIndexes);
    }

    private Set<String> getParentRealms(String realm) throws SMSException {
        Set<String> results = new HashSet<String>();
        SSOToken adminToken = SubjectUtils.getSSOToken(superAdminSubject);
        OrganizationConfigManager ocm = new OrganizationConfigManager(
            adminToken, realm);
        while (true) {
            ocm = ocm.getParentOrgConfigManager();
            String name = DNMapper.orgNameToRealmName(
                ocm.getOrganizationName());
            results.add(name);
            if (name.equals("/")) {
                break;
            }
        }
        return results;
    }

    private Set<String> getPeerRealms(String realm) throws SMSException {
        SSOToken adminToken = SubjectUtils.getSSOToken(superAdminSubject);
        OrganizationConfigManager ocm = new OrganizationConfigManager(
            adminToken, realm);
        OrganizationConfigManager parentOrg = ocm.getParentOrgConfigManager();
        String base = DNMapper.orgNameToRealmName(
            parentOrg.getOrganizationName());
        if (!base.endsWith("/")) {
            base += "/";
        }
        Set<String> results = new HashSet<String>();
        Set<String> subrealms = parentOrg.getSubOrganizationNames();
        for (String s : subrealms) {
            results.add(base + s);
        }
        results.remove(getRealm());
        return results;
    }

    static Set<String> getOrgAliasMappingResources(
        String realm, String applicationTypeName
    ) throws SMSException {
        Set<String> results = new HashSet<String>();

        if (applicationTypeName.equalsIgnoreCase(
                ApplicationTypeManager.URL_APPLICATION_TYPE_NAME)) {
            SSOToken adminToken = (SSOToken) AccessController.doPrivileged(
                AdminTokenAction.getInstance());

            if (isOrgAliasMappingResourceEnabled(adminToken)) {
                OrganizationConfigManager m = new
                    OrganizationConfigManager(adminToken, realm);
                Map<String, Set<String>> map = m.getAttributes(
                    PolicyManager.ID_REPO_SERVICE);
                Set<String> orgAlias = map.get(PolicyManager.ORG_ALIAS);

                if ((orgAlias != null) && !orgAlias.isEmpty()) {
                    for (String s : orgAlias) {
                        results.add(PolicyManager.ORG_ALIAS_URL_HTTPS_PREFIX +
                            s.trim() + PolicyManager.ORG_ALIAS_URL_SUFFIX);
                        results.add(PolicyManager.ORG_ALIAS_URL_HTTP_PREFIX +
                            s.trim() + PolicyManager.ORG_ALIAS_URL_SUFFIX);
                    }
                }
            }
        }
        return results;
    }

    public static boolean isOrgAliasMappingResourceEnabled(SSOToken adminToken)
    {
        try {
            ServiceSchemaManager ssm = new ServiceSchemaManager(
                PolicyConfig.POLICY_CONFIG_SERVICE, adminToken);
            ServiceSchema globalSchema = ssm.getGlobalSchema();
            Map<String, Set<String>> map =
                globalSchema.getAttributeDefaults();
            Set<String> values = map.get(
                PolicyConfig.ORG_ALIAS_MAPPED_RESOURCES_ENABLED);
            if ((values != null) && !values.isEmpty()) {
                String val = values.iterator().next();
                return Boolean.valueOf(val);
            } else {
                return false;
            }
        } catch (SMSException ex) {
            PolicyConstants.DEBUG.error(
                "OpenSSOIndexStore.isOrgAliasMappingResourceEnabled", ex);
            return false;
        } catch (SSOException ex) {
            PolicyConstants.DEBUG.error(
                "OpenSSOIndexStore.isOrgAliasMappingResourceEnabled", ex);
            return false;
        }
    }

    String getRealmDN() {
        return (realmDN);
    }

    @Override
    public boolean hasPrivilgesWithApplication(
        String realm, String applName) throws EntitlementException {
        return dataStore.hasPrivilgesWithApplication(getAdminSubject(), realm,
                applName);
    }

    public class SearchTask implements Runnable {
        private BufferedIterator iterator;
        private ResourceSearchIndexes indexes;
        private Set<String> subjectIndexes;
        private boolean bSubTree;

        public SearchTask(
            BufferedIterator iterator,
            ResourceSearchIndexes indexes,
            Set<String> subjectIndexes,
            boolean bSubTree
        ) {
            this.iterator = iterator;
            this.indexes = indexes;
            this.subjectIndexes = subjectIndexes;
            this.bSubTree = bSubTree;
        }

        public void run() {
            try {
                dataStore.search(getRealmDN(), iterator,
                    indexes, subjectIndexes, bSubTree);
            } catch (EntitlementException ex) {
                iterator.isDone();
                PolicyConstants.DEBUG.error(
                    "OpenSSOIndexStore.SearchTask.runPolicy", ex);
            }
        }
    }

    // SMS Listener to clear cache when realms are deleted
    private static class EntitlementsListener implements ServiceListener {

        public void schemaChanged(String serviceName, String version) {
        }

        public void globalConfigChanged(String serviceName, String version,
            String groupName, String serviceComponent, int type) {
        }

        public void organizationConfigChanged(String serviceName,
            String version, String orgName, String groupName,
            String serviceComponent, int type) {
            if ((type == ServiceListener.REMOVED) &&
                ((serviceComponent == null) ||
                (serviceComponent.trim().length() == 0) ||
                serviceComponent.equals("/"))) {
                // Realm has been deleted
                getApplicationService(SUPER_ADMIN_SUBJECT, orgName).clearCache();
            }
        }
    }
}
