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
 * $Id: DataStore.java,v 1.13 2010/01/20 17:01:35 veiming Exp $
 *
 * Portions Copyrighted 2012-2017 ForgeRock AS.
 */
package com.sun.identity.entitlement.opensso;

import static java.util.Collections.emptySet;
import static org.forgerock.openam.utils.CollectionUtils.isNotEmpty;

import java.security.AccessController;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.naming.NamingException;
import javax.security.auth.Subject;

import org.forgerock.openam.entitlement.PolicyConstants;
import org.forgerock.openam.ldap.LDAPUtils;
import org.forgerock.opendj.ldap.DN;
import org.json.JSONException;
import org.json.JSONObject;

import com.iplanet.sso.SSOException;
import com.iplanet.sso.SSOToken;
import com.sun.identity.entitlement.Entitlement;
import com.sun.identity.entitlement.EntitlementException;
import com.sun.identity.entitlement.IPrivilege;
import com.sun.identity.entitlement.Privilege;
import com.sun.identity.entitlement.ReferralPrivilege;
import com.sun.identity.entitlement.ResourceSaveIndexes;
import com.sun.identity.entitlement.ResourceSearchIndexes;
import com.sun.identity.entitlement.SubjectAttributesManager;
import com.sun.identity.entitlement.util.NetworkMonitor;
import com.sun.identity.security.AdminTokenAction;
import com.sun.identity.shared.BufferedIterator;
import com.sun.identity.shared.stats.Stats;
import com.sun.identity.sm.DNMapper;
import com.sun.identity.sm.SMSDataEntry;
import com.sun.identity.sm.SMSEntry;
import com.sun.identity.sm.SMSException;
import com.sun.identity.sm.ServiceConfig;
import com.sun.identity.sm.ServiceConfigManager;

/**
 * This class *talks* to SMS to get the configuration information.
 */
public class DataStore {
    private static DataStore instance = new DataStore();
    public static final String POLICY_STORE = "default";
    public static final String REFERRAL_STORE = "referrals";

    private static final String SERVICE_NAME = "sunEntitlementIndexes";
    private static final String REALM_DN_TEMPLATE =
         "ou={0},ou=default,ou=OrganizationConfig,ou=1.0,ou=" + SERVICE_NAME +
         ",ou=services,{1}";
    private static final String SUBJECT_INDEX_KEY = "subjectindex";
    private static final String HOST_INDEX_KEY = "hostindex";
    private static final String PATH_INDEX_KEY = "pathindex";
    private static final String PATH_PARENT_INDEX_KEY = "pathparentindex";
    private static final String SERIALIZABLE_INDEX_KEY = "serializable";

    public static final String REFERRAL_REALMS = "referralrealms";
    public static final String REFERRAL_APPLS = "referralappls";

    private static final String NO_FILTER = "(objectClass=*)";
    private static final int NO_LIMIT = 0;
    private static final boolean NOT_SORTED = false;
    private static final Set<String> NO_EXCLUSIONS = emptySet();

    private static final String SUBJECT_FILTER_TEMPLATE =
        "(" + SMSEntry.ATTR_XML_KEYVAL + "=" + SUBJECT_INDEX_KEY + "={0})";
    private static final String HOST_FILTER_TEMPLATE =
        "(" + SMSEntry.ATTR_XML_KEYVAL + "=" + HOST_INDEX_KEY + "={0})";
    private static final String PATH_FILTER_TEMPLATE =
        "(" + SMSEntry.ATTR_XML_KEYVAL + "=" + PATH_INDEX_KEY + "={0})";
    private static final String PATH_PARENT_FILTER_TEMPLATE =
        "(" + SMSEntry.ATTR_XML_KEYVAL + "=" + PATH_PARENT_INDEX_KEY + "={0})";

    private static final NetworkMonitor DB_MONITOR_PRIVILEGE =
        NetworkMonitor.getInstance("dbLookupPrivileges");
    private static final NetworkMonitor DB_MONITOR_REFERRAL =
        NetworkMonitor.getInstance("dbLookupReferrals");
    private static final String HIDDEN_REALM_DN =
        "o=sunamhiddenrealmdelegationservicepermissions,ou=services,";

    private static SSOToken adminToken = (SSOToken)
        AccessController.doPrivileged(AdminTokenAction.getInstance());

    static {
        // Initialize statistics collection
        Stats stats = Stats.getInstance("Entitlements");
        EntitlementsStats es = new EntitlementsStats(stats);
        stats.addStatsListener(es);
    }

    private DataStore() {
    }

    public static DataStore getInstance() {
        return instance;
    }
    /**
     * Returns distingished name of a privilege.
     *
     * @param name Privilege name.
     * @param realm Realm name.
     * @param indexName Index name.
     * @return the distingished name of a privilege.
     */
    public static String getPrivilegeDistinguishedName(
        String name,
        String realm,
        String indexName) {
        return "ou=" + name + "," + getSearchBaseDN(realm, indexName);
    }

    /**
     * Returns the base search DN.
     *
     * @param realm Realm name.
     * @param indexName Index name.
     * @return the base search DN.
     */
    public static String getSearchBaseDN(String realm, String indexName) {
        if (indexName == null) {
            indexName = POLICY_STORE;
        }
        String dn = LDAPUtils.isDN(realm) ? realm : DNMapper.orgNameToDN(realm);
        Object[] args = {indexName, dn};
        return MessageFormat.format(REALM_DN_TEMPLATE, args);
    }

    private String createDefaultSubConfig(
        SSOToken adminToken,
        String realm,
        String indexName)
        throws SMSException, SSOException {
        if (indexName == null) {
            indexName = POLICY_STORE;
        }
        ServiceConfig orgConf = getOrgConfig(adminToken, realm);

        Set<String> subConfigNames = orgConf.getSubConfigNames();
        if (!subConfigNames.contains(indexName)) {
            orgConf.addSubConfig(indexName, "type", 0,
                Collections.EMPTY_MAP);
        }
        ServiceConfig defSubConfig = orgConf.getSubConfig(indexName);
        return defSubConfig.getDN();
    }

    private ServiceConfig getOrgConfig(SSOToken adminToken, String realm)
        throws SMSException, SSOException {
        ServiceConfigManager mgr = new ServiceConfigManager(
            SERVICE_NAME, adminToken);
        ServiceConfig orgConf = mgr.getOrganizationConfig(realm, null);
        if (orgConf == null) {
            mgr.createOrganizationConfig(realm, null);
        }
        return orgConf;
    }

    /**
     * Adds a privilege.
     *
     * @param adminSubject Admin Subject who has the rights to write to
     *        datastore.
     * @param realm Realm name.
     * @param p Privilege object.
     * @return the DN of added privilege.
     * @throws com.sun.identity.entitlement.EntitlementException if privilege
     * cannot be added.
     */
    public String add(Subject adminSubject, String realm, Privilege p)
        throws EntitlementException {

        ResourceSaveIndexes indexes =
            p.getEntitlement().getResourceSaveIndexes(adminSubject, realm);
        Set<String> subjectIndexes =
            SubjectAttributesManager.getSubjectSearchIndexes(p);

        String dn = null;
        try {
            createDefaultSubConfig(adminToken, realm, null);
            dn = getPrivilegeDistinguishedName(p.getName(), realm, null);

            if (SMSEntry.checkIfEntryExists(dn, adminToken)) {
                throw new EntitlementException(EntitlementException.POLICY_ALREADY_EXISTS);
            }

            SMSEntry s = new SMSEntry(adminToken, dn);
            Map<String, Set<String>> map = new HashMap<String, Set<String>>();

            Set<String> searchable = new HashSet<String>();
            map.put(SMSEntry.ATTR_XML_KEYVAL, searchable);
            searchable.add(Privilege.RESOURCE_TYPE_UUID_ATTRIBUTE + "=" + p.getResourceTypeUuid());

            if (indexes !=null) {
                for (String i : indexes.getHostIndexes()) {
                    searchable.add(HOST_INDEX_KEY + "=" + i);
                }
                for (String i : indexes.getPathIndexes()) {
                    searchable.add(PATH_INDEX_KEY + "=" + i);
                }
                for (String i : indexes.getParentPathIndexes()) {
                    searchable.add(PATH_PARENT_INDEX_KEY + "=" + i);
                }
                for (String i : subjectIndexes) {
                    searchable.add(SUBJECT_INDEX_KEY + "=" + i);
                }
            }

            Set<String> setServiceID = new HashSet<String>(2);
            map.put(SMSEntry.ATTR_SERVICE_ID, setServiceID);
            setServiceID.add("indexes");

            Set<String> set = new HashSet<String>(2);
            map.put(SMSEntry.ATTR_KEYVAL, set);
            set.add(SERIALIZABLE_INDEX_KEY + "=" + p.toJSONObject().toString());

            Set<String> setObjectClass = new HashSet<String>(4);
            map.put(SMSEntry.ATTR_OBJECTCLASS, setObjectClass);
            setObjectClass.add(SMSEntry.OC_TOP);
            setObjectClass.add(SMSEntry.OC_SERVICE_COMP);

            Set<String> info = new HashSet<String>(8);

            String privilegeName = p.getName();
            if (privilegeName != null) {
                info.add(Privilege.NAME_ATTRIBUTE + "=" + privilegeName);
            }

            String privilegeDesc = p.getDescription();
            if (privilegeDesc != null) {
                info.add(Privilege.DESCRIPTION_ATTRIBUTE + "=" + privilegeDesc);
            }

            String createdBy = p.getCreatedBy();
            if (createdBy != null) {
                info.add(Privilege.CREATED_BY_ATTRIBUTE + "=" + createdBy);
            }

            String lastModifiedBy = p.getLastModifiedBy();
            if (lastModifiedBy != null) {
                info.add(Privilege.LAST_MODIFIED_BY_ATTRIBUTE + "=" +
                    lastModifiedBy);
            }

            long creationDate = p.getCreationDate();
            if (creationDate > 0) {
                String data = Long.toString(creationDate) + "=" +
                    Privilege.CREATION_DATE_ATTRIBUTE;
                info.add(data);
                info.add("|" + data);
            }

            long lastModifiedDate = p.getLastModifiedDate();
            if (lastModifiedDate > 0) {
                String data = Long.toString(lastModifiedDate) + "=" +
                    Privilege.LAST_MODIFIED_DATE_ATTRIBUTE;
                info.add(data);
                info.add("|" + data);
            }

            Entitlement ent = p.getEntitlement();
            info.add(Privilege.APPLICATION_ATTRIBUTE + "=" +
                ent.getApplicationName());
            for (String a : p.getApplicationIndexes()) {
                info.add(Privilege.APPLICATION_ATTRIBUTE + "=" + a);
            }
            map.put("ou", info);

            s.setAttributes(map);
            s.save();
        } catch (JSONException e) {
            throw new EntitlementException(210, e);
        } catch (SSOException e) {
            throw new EntitlementException(210, e);
        } catch (SMSException e) {
            throw new EntitlementException(210, e);
        }
        return dn;
    }
    /**
     * Adds a referral.
     *
     * @param adminSubject Admin Subject who has the rights to write to
     *        datastore.
     * @param realm Realm name.
     * @param referral Referral Privilege object.
     * @return the DN of added privilege.
     * @throws EntitlementException if privilege cannot be added.
     */
    public String addReferral(
        Subject adminSubject,
        String realm,
        ReferralPrivilege referral
    ) throws EntitlementException {
        ResourceSaveIndexes indexes = referral.getResourceSaveIndexes(
                adminSubject, realm);
        SSOToken token = getSSOToken(adminSubject);
        String dn = null;
        try {
            createDefaultSubConfig(token, realm, REFERRAL_STORE);
            dn = getPrivilegeDistinguishedName(referral.getName(), realm,
                REFERRAL_STORE);

            SMSEntry s = new SMSEntry(token, dn);
            Map<String, Set<String>> map = new HashMap<String, Set<String>>();

            Set<String> searchable = new HashSet<String>();
            map.put(SMSEntry.ATTR_XML_KEYVAL, searchable);

            if (indexes != null) {
                for (String i : indexes.getHostIndexes()) {
                    searchable.add(HOST_INDEX_KEY + "=" + i);
                }
                for (String i : indexes.getPathIndexes()) {
                    searchable.add(PATH_INDEX_KEY + "=" + i);
                }
                for (String i : indexes.getParentPathIndexes()) {
                    searchable.add(PATH_PARENT_INDEX_KEY + "=" + i);
                }
            }

            Set<String> setServiceID = new HashSet<String>(2);
            map.put(SMSEntry.ATTR_SERVICE_ID, setServiceID);
            setServiceID.add("indexes");

            Set<String> set = new HashSet<String>(2);
            map.put(SMSEntry.ATTR_KEYVAL, set);
            set.add(SERIALIZABLE_INDEX_KEY + "=" + referral.toJSON());

            Set<String> setObjectClass = new HashSet<String>(4);
            map.put(SMSEntry.ATTR_OBJECTCLASS, setObjectClass);
            setObjectClass.add(SMSEntry.OC_TOP);
            setObjectClass.add(SMSEntry.OC_SERVICE_COMP);

            Set<String> info = new HashSet<String>(8);

            String privilegeName = referral.getName();
            if (privilegeName != null) {
                info.add(Privilege.NAME_ATTRIBUTE + "=" + privilegeName);
            }

            String privilegeDesc = referral.getDescription();
            if (privilegeDesc != null) {
                info.add(Privilege.DESCRIPTION_ATTRIBUTE + "=" + privilegeDesc);
            }

            String createdBy = referral.getCreatedBy();
            if (createdBy != null) {
                info.add(Privilege.CREATED_BY_ATTRIBUTE + "=" + createdBy);
            }

            String lastModifiedBy = referral.getLastModifiedBy();
            if (lastModifiedBy != null) {
                info.add(Privilege.LAST_MODIFIED_BY_ATTRIBUTE + "=" +
                    lastModifiedBy);
            }

            long creationDate = referral.getCreationDate();
            if (creationDate > 0) {
                String data = Long.toString(creationDate) + "=" +
                    Privilege.CREATION_DATE_ATTRIBUTE;
                info.add(data);
                info.add("|" + data);
            }

            long lastModifiedDate = referral.getLastModifiedDate();
            if (lastModifiedDate > 0) {
                String data = Long.toString(lastModifiedDate) + "=" +
                    Privilege.LAST_MODIFIED_DATE_ATTRIBUTE;
                info.add(data);
                info.add("|" + data);
            }

            for (String rlm : referral.getRealms()) {
                info.add(REFERRAL_REALMS + "=" + rlm);
            }
            for (String n : referral.getApplicationTypeNames(adminSubject,
                realm)) {
                info.add(REFERRAL_APPLS + "=" + n);
            }
            for (String n : referral.getMapApplNameToResources().keySet()) {
                info.add(Privilege.APPLICATION_ATTRIBUTE + "=" + n);
            }
            map.put("ou", info);

            s.setAttributes(map);
            s.save();
        } catch (SSOException e) {
            throw new EntitlementException(270, e);
        } catch (SMSException e) {
            throw new EntitlementException(270, e);
        }
        return dn;
    }

    /**
     * Removes privilege.
     *
     * @param adminSubject Admin Subject who has the rights to write to
     *        datastore.
     * @param realm Realm name.
     * @param name Privilege name.
     * @throws com.sun.identity.entitlement.EntitlementException if privilege
     * cannot be removed.
     */
    public void remove(
        Subject adminSubject,
        String realm,
        String name
    ) throws EntitlementException {
        SSOToken token = getSSOToken(adminSubject);

        if (token == null) {
            Object[] arg = {name};
            throw new EntitlementException(55, arg);
        }

        String dn = null;
        try {
            dn = getPrivilegeDistinguishedName(name, realm, null);

            if (SMSEntry.checkIfEntryExists(dn, token)) {
                SMSEntry s = new SMSEntry(token, dn);
                s.delete();
            }
        } catch (SMSException e) {
            Object[] arg = {dn};
            throw new EntitlementException(51, arg, e);
        } catch (SSOException e) {
            throw new EntitlementException(10, null, e);
        }

    }

    /**
     * Removes referral privilege.
     *
     * @param adminSubject Admin Subject who has the rights to write to
     *        datastore.
     * @param realm Realm name.
     * @param name Referral privilege name.
     * @throws EntitlementException if privilege cannot be removed.
     */
    public void removeReferral(
        Subject adminSubject,
        String realm,
        String name
    ) throws EntitlementException {
        SSOToken token = getSSOToken(adminSubject);

        if (token == null) {
            Object[] arg = {name};
            throw new EntitlementException(55, arg);
        }

        String dn = null;
        try {
            dn = getPrivilegeDistinguishedName(name, realm, REFERRAL_STORE);

            if (SMSEntry.checkIfEntryExists(dn, token)) {
                SMSEntry s = new SMSEntry(token, dn);
                s.delete();
            }
        } catch (SMSException e) {
            Object[] arg = {dn};
            throw new EntitlementException(51, arg, e);
        } catch (SSOException e) {
            throw new EntitlementException(10, null, e);
        }
    }

    /**
     * Returns a set of privilege names that satifies a search filter.
     *
     * @param adminSubject Subject who has the rights to read datastore.
     * @param realm Realm name
     * @param filter Search filter.
     * @param numOfEntries Number of max entries.
     * @param sortResults <code>true</code> to have result sorted.
     * @param ascendingOrder <code>true</code> to have result sorted in
     * ascending order.
     * @return a set of privilege names that satifies a search filter.
     * @throws EntitlementException if search failed.
     */
    public Set<String> search(
        Subject adminSubject,
        String realm,
        String filter,
        int numOfEntries,
        boolean sortResults,
        boolean ascendingOrder
    ) throws EntitlementException {
        return search(adminSubject, realm, filter, numOfEntries, sortResults, ascendingOrder, null);
    }
    
    /**
     * Returns a set of referral privilege names that satifies a search filter.
     *
     * @param adminSubject Subject who has the rights to read datastore.
     * @param realm Realm name
     * @param filter Search filter.
     * @param numOfEntries Number of max entries.
     * @param sortResults <code>true</code> to have result sorted.
     * @param ascendingOrder <code>true</code> to have result sorted in
     * ascending order.
     * @return a set of privilege names that satifies a search filter.
     * @throws EntitlementException if search failed.
     */
    public Set<String> searchReferral(
        Subject adminSubject,
        String realm,
        String filter,
        int numOfEntries,
        boolean sortResults,
        boolean ascendingOrder
    ) throws EntitlementException {
        return search(adminSubject, realm, filter, numOfEntries, sortResults, ascendingOrder, REFERRAL_STORE);
    }

    private Set<String> search(Subject adminSubject, String realm, String filter, int numOfEntries, boolean sortResults, boolean ascendingOrder, String indexName) throws EntitlementException {
        try {
            SSOToken token = getSSOToken(adminSubject);

            if (token == null) {
                throw new EntitlementException(EntitlementException.UNABLE_SEARCH_PRIVILEGES_MISSING_TOKEN);
            }

            String baseDNString = getSearchBaseDN(realm, indexName);

            if (SMSEntry.checkIfEntryExists(baseDNString, token)) {
                DN baseDN = DN.valueOf(baseDNString);
                return LDAPUtils.collectNonIdenticalValues(baseDN,
                        SMSEntry.search(token, baseDNString, filter, numOfEntries, 0, sortResults, ascendingOrder));
            } else {
                return emptySet();
            }
        } catch (SMSException | NamingException ex) {
            throw new EntitlementException(EntitlementException.UNABLE_SEARCH_PRIVILEGES, ex);
        }
    }

    public boolean hasPrivilgesWithApplication(
        Subject adminSubject,
        String realm,
        String applName
    ) throws EntitlementException {
        SSOToken token = getSSOToken(adminSubject);

         //Search privilege
         String filter = "(ou=" + Privilege.APPLICATION_ATTRIBUTE + "=" +
             applName + ")";
         String baseDN = getSearchBaseDN(realm, null);
         if (hasEntries(token, baseDN, filter)) {
             return true;
         }

         //Search referral privilege
         baseDN = getSearchBaseDN(realm, REFERRAL_STORE);
         if (hasEntries(token, baseDN, filter)) {
             return true;
         }
         
         //Search delegation privilege
         baseDN = getSearchBaseDN(getHiddenRealmDN(), null);
         if (hasEntries(token, baseDN, filter)) {
             return true;
         }

         return false;
    }

     private static String getHiddenRealmDN() {
        return HIDDEN_REALM_DN + SMSEntry.getRootSuffix();
    }

    private boolean hasEntries(SSOToken token, String baseDN, String filter)
        throws EntitlementException {
         if (SMSEntry.checkIfEntryExists(baseDN, token)) {
             try {
                 Set<String> dns = SMSEntry.search(token, baseDN, filter,
                     0, 0, false, false);
                 if ((dns != null) && !dns.isEmpty()) {
                     return true;
                 }
             } catch (SMSException e) {
                 Object[] arg = {baseDN};
                 throw new EntitlementException(52, arg, e);
             }
         }
         return false;
    }


    /**
     * Populates the given iterator with policies that satisfy the resource and subject indexes.
     *
     * @param realm Realm name
     * @param iterator Buffered iterator to have the result fed to it.
     * @param indexes Resource search indexes.
     * @param subjectIndexes Subject search indexes.
     * @param bSubTree <code>true</code> to do sub tree search
     */
    public void search(
        String realm,
        BufferedIterator iterator,
        ResourceSearchIndexes indexes,
        Set<String> subjectIndexes,
        boolean bSubTree
    ) throws EntitlementException {
        searchPrivileges(realm, iterator, indexes, subjectIndexes, bSubTree);
    }

    /**
     * Retrieves an individual privilege from the data store. The privilege is returned by the method and
     * also added to the passed in iterator.
     *
     * @param realm Realm in which the privilege exists.
     * @param privilegeIdentifier The identifier of the privilege to retrieve.
     * @return the privilege.
     * @throws EntitlementException if there were issues retrieving the privilege from the data store.
     */
    public IPrivilege getPrivilege(String realm, String privilegeIdentifier)
            throws EntitlementException {
        final String privilegeDN = getPrivilegeDistinguishedName(privilegeIdentifier, realm, null);

        final long start = DB_MONITOR_PRIVILEGE.start();

        final SSOToken token = AccessController.doPrivileged(AdminTokenAction.getInstance());
        Privilege privilege = null;

        try {
            final Iterator i = SMSEntry.search(token, privilegeDN, NO_FILTER, NO_LIMIT, NO_LIMIT,
                    NOT_SORTED, NOT_SORTED, NO_EXCLUSIONS);
            while (i.hasNext()) {
                SMSDataEntry e = (SMSDataEntry) i.next();
                privilege = Privilege.getInstance(new JSONObject(e.getAttributeValue(SERIALIZABLE_INDEX_KEY)));
            }
        } catch (SMSException e) {
            Object[] arg = {privilegeDN};
            throw new EntitlementException(52, arg, e);
        } catch (JSONException e) {
            Object[] arg = {privilegeDN};
            throw new EntitlementException(52, arg, e);
        }

        DB_MONITOR_PRIVILEGE.end(start);

        return privilege;
    }

    private void searchPrivileges(
            String realm,
            BufferedIterator iterator,
            ResourceSearchIndexes indexes,
            Set<String> subjectIndexes,
            boolean bSubTree
    ) throws EntitlementException {
        String filter = getFilter(indexes, subjectIndexes, bSubTree);
        String baseDN = getSearchBaseDN(realm, null);

        if (PolicyConstants.DEBUG.messageEnabled()) {
            PolicyConstants.DEBUG.message(
                    "[PolicyEval] DataStore.searchPrivileges");
            PolicyConstants.DEBUG.message(
                    "[PolicyEval] search filter: " + filter);
            PolicyConstants.DEBUG.message(
                    "[PolicyEval] search DN: " + baseDN);
        }

        if (filter != null) {
            SSOToken token = AccessController.doPrivileged(
                    AdminTokenAction.getInstance());

            long start = DB_MONITOR_PRIVILEGE.start();

            if (SMSEntry.checkIfEntryExists(baseDN, token)) {
                try {
                    Iterator i = SMSEntry.search(
                            token, baseDN, filter, NO_LIMIT, NO_LIMIT, NOT_SORTED, NOT_SORTED, null);
                    while (i.hasNext()) {
                        SMSDataEntry e = (SMSDataEntry) i.next();
                        Privilege privilege = Privilege.getInstance(
                                new JSONObject(e.getAttributeValue(
                                        SERIALIZABLE_INDEX_KEY)));
                        iterator.add(privilege);
                    }
                } catch (JSONException | SMSException e) {
                    Object[] arg = {baseDN};
                    throw new EntitlementException(52, arg, e);
                }
            }

            DB_MONITOR_PRIVILEGE.end(start);
        }
    }

    List<Privilege> findPoliciesByRealm(String realm) throws EntitlementException {
        return findPolicies(realm, "(sunserviceID=indexes)");
    }

    List<Privilege> findPoliciesByRealmAndApplication(String realm, String application) throws EntitlementException {
        return findPolicies(realm, String.format("(&(sunserviceID=indexes)(ou=application=%s))", application));
    }

    List<Privilege> findAllPoliciesByRealmAndSubjectIndex(String realm, Set<String> subjectIndexes) throws EntitlementException {
        StringBuilder filter = new StringBuilder("(|");
        for (String index : subjectIndexes) {
            filter.append('(')
                    .append(SMSEntry.ATTR_XML_KEYVAL)
                    .append('=')
                    .append(SUBJECT_INDEX_KEY)
                    .append('=')
                    .append(escapeCharactersInFilter(index))
                    .append(')');
        }
        filter.append(')');

        return findPolicies(realm, filter.toString());
    }

    private List<Privilege> findPolicies(String realm, String ldapFilter) throws EntitlementException {
        List<Privilege> results = new ArrayList<>();

        String baseDN = getSearchBaseDN(realm, null);
        SSOToken token = AccessController.doPrivileged(AdminTokenAction.getInstance());

        if (SMSEntry.checkIfEntryExists(baseDN, token)) {
            try {
                @SuppressWarnings("unchecked")
                Iterator<SMSDataEntry> iterator = SMSEntry
                        .search(token, baseDN, ldapFilter, NO_LIMIT, NO_LIMIT, NOT_SORTED, NOT_SORTED, emptySet());

                while (iterator.hasNext()) {
                    SMSDataEntry entry = iterator.next();
                    String policyJson = entry.getAttributeValue(SERIALIZABLE_INDEX_KEY);
                    results.add(Privilege.getInstance(new JSONObject(policyJson)));
                }
            } catch (JSONException | SMSException e) {
                throw new EntitlementException(EntitlementException.UNABLE_SEARCH_PRIVILEGES, e);
            }
        }

        return results;
    }

    private static String getFilter(Set<String> subjectIndexes) {
        StringBuilder subjectFilter = new StringBuilder();

        if (isNotEmpty(subjectIndexes)) {
            subjectFilter.append("(|");

            for (String subjectIndex : subjectIndexes) {
                subjectFilter.append(MessageFormat
                        .format(SUBJECT_FILTER_TEMPLATE, escapeCharactersInFilter(subjectIndex)));
            }

            subjectFilter.append(')');
        }

        return subjectFilter.toString();
    }

    static String getFilter(
        ResourceSearchIndexes indexes,
        Set<String> subjectIndexes,
        boolean bSubTree
    ) {
        StringBuilder filter = new StringBuilder();
        filter.append(getFilter(subjectIndexes));

        Set<String> hostIndexes = indexes.getHostIndexes();
        StringBuilder hostBuffer = new StringBuilder();
        if ((hostIndexes != null) && !hostIndexes.isEmpty()) {
            for (String h : indexes.getHostIndexes()) {
                hostBuffer.append(MessageFormat.format(HOST_FILTER_TEMPLATE, escapeCharactersInFilter(h)));
            }
        }
        if (hostBuffer.length() > 0) {
            filter.append("(|").append(hostBuffer.toString()).append(")");
        }

        StringBuilder pathBuffer = new StringBuilder();
        Set<String> pathIndexes = indexes.getPathIndexes();

        if ((pathIndexes != null) && !pathIndexes.isEmpty()) {
            for (String p : pathIndexes) {
                pathBuffer.append(MessageFormat.format(PATH_FILTER_TEMPLATE, escapeCharactersInFilter(p)));
            }
        }

        if (bSubTree) {
            Set<String> parentPathIndexes = indexes.getParentPathIndexes();
            if ((parentPathIndexes != null) && !parentPathIndexes.isEmpty()) {
                for (String p : parentPathIndexes) {
                    pathBuffer.append(MessageFormat.format(PATH_PARENT_FILTER_TEMPLATE, escapeCharactersInFilter(p)));
                }
            }
        }
        if (pathBuffer.length() > 0) {
            filter.append("(|").append(pathBuffer.toString()).append(")");
        }

        String result = filter.toString();
        return (result.length() > 0) ? "(&" + result + ")" : null;
    }


    public Set<ReferralPrivilege> searchReferrals(
        SSOToken adminToken,
        String realm,
        String filter
    ) throws EntitlementException {
        Set<ReferralPrivilege> results = new HashSet<ReferralPrivilege>();
        String baseDN = getSearchBaseDN(realm, REFERRAL_STORE);

        if (SMSEntry.checkIfEntryExists(baseDN, adminToken)) {
            try {
                Iterator i = SMSEntry.search(
                    adminToken, baseDN, filter, NO_LIMIT, NO_LIMIT, NOT_SORTED, NOT_SORTED, NO_EXCLUSIONS);
                while (i.hasNext()) {
                    SMSDataEntry e = (SMSDataEntry) i.next();
                    ReferralPrivilege referral = ReferralPrivilege.getInstance(
                        new JSONObject(e.getAttributeValue(
                        SERIALIZABLE_INDEX_KEY)));
                    results.add(referral);
                }
            } catch (JSONException e) {
                Object[] arg = {baseDN};
                throw new EntitlementException(52, arg, e);
            } catch (SMSException e) {
                Object[] arg = {baseDN};
                throw new EntitlementException(52, arg, e);
            }
        }
        return results;
    }

    private SSOToken getSSOToken(Subject subject) {
        if (PolicyConstants.SUPER_ADMIN_SUBJECT.equals(subject)) {
            return adminToken;
        }
        return SubjectUtils.getSSOToken(subject);
    }

    private static String escapeCharactersInFilter(String assertionValue) {
        StringBuilder result = new StringBuilder(assertionValue.length());
        for (int cursor = 0; cursor < assertionValue.length(); cursor++) {
            char nextChar = assertionValue.charAt(cursor);
            switch (nextChar) {
                case '*':
                    result.append("\\2a");
                    break;
                case '(':
                    result.append("\\28");
                    break;
                case ')':
                    result.append("\\29");
                    break;
                case '/':
                    result.append("\\2f");
                    break;
                case '\\':
                    result.append("\\5c");
                    break;
                case '\0':
                    result.append("\\00");
                    break;
                default:
                    result.append(nextChar);
                    break;
            }
        }
        return result.toString();
    }
}
