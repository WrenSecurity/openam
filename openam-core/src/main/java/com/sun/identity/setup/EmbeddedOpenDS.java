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
 * $Id: EmbeddedOpenDS.java,v 1.27 2010/01/15 01:22:39 goodearth Exp $
 *
 * Portions Copyrighted 2010-2017 ForgeRock AS.
 */

package com.sun.identity.setup;

import static com.sun.identity.setup.SetupConstants.*;
import static org.forgerock.openam.ldap.LDAPRequests.newSingleEntrySearchRequest;
import static org.forgerock.opendj.server.embedded.ConfigParameters.configParams;
import static org.forgerock.opendj.server.embedded.ConnectionParameters.connectionParams;
import static org.forgerock.opendj.server.embedded.EmbeddedDirectoryServer.manageEmbeddedDirectoryServer;
import static org.forgerock.opendj.server.embedded.EmbeddedDirectoryServer.manageEmbeddedDirectoryServerForRestrictedOps;
import static org.forgerock.opendj.server.embedded.ImportParameters.importParams;
import static org.forgerock.opendj.server.embedded.RebuildIndexParameters.rebuildIndexParams;
import static org.forgerock.opendj.server.embedded.ReplicationParameters.replicationParams;

import javax.servlet.ServletContext;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import com.sun.identity.common.ShutdownManager;
import com.sun.identity.shared.debug.Debug;
import com.sun.identity.sm.SMSEntry;
import org.forgerock.guava.common.io.ByteStreams;
import org.forgerock.i18n.LocalizableMessage;
import org.forgerock.openam.ldap.LDAPRequests;
import org.forgerock.openam.utils.IOUtils;
import org.forgerock.opendj.ldap.Attribute;
import org.forgerock.opendj.ldap.Attributes;
import org.forgerock.opendj.ldap.ByteString;
import org.forgerock.opendj.ldap.Connection;
import org.forgerock.opendj.ldap.Dn;
import org.forgerock.opendj.ldap.Modification;
import org.forgerock.opendj.ldap.ModificationType;
import org.forgerock.opendj.ldap.requests.ModifyRequest;
import org.forgerock.opendj.ldap.responses.SearchResultEntry;
import org.forgerock.opendj.server.config.client.BackendCfgClient;
import org.forgerock.opendj.server.config.client.PluggableBackendCfgClient;
import org.forgerock.opendj.server.config.client.ReplicationDomainCfgClient;
import org.forgerock.opendj.server.config.client.ReplicationServerCfgClient;
import org.forgerock.opendj.server.config.client.ReplicationSynchronizationProviderCfgClient;
import org.forgerock.opendj.server.config.client.RootCfgClient;
import org.forgerock.opendj.server.embedded.ConnectionParameters;
import org.forgerock.opendj.server.embedded.EmbeddedDirectoryServer;
import org.forgerock.opendj.server.embedded.EmbeddedDirectoryServerException;
import org.forgerock.opendj.server.embedded.ReplicationParameters;
import org.forgerock.opendj.server.embedded.SetupParameters;
import org.forgerock.util.thread.listener.ShutdownListener;
import org.forgerock.util.thread.listener.ShutdownPriority;
import org.opends.server.extensions.SaltedSHA512PasswordStorageScheme;
import org.opends.server.util.StaticUtils;

/**
 * Represents an embedded OpenDJ server.
 * <p>
 * The embedded server must be initialized before operations can be performed on it.
 * Initialization is done with {@code initialize()} method and/or {@code setup()} method.
 * <p>
 * Most of the calls are performed by <code>AMSetupServlet</code> class at different points : initial installation,
 * normal startup and normal shutdown of the embedded <code>OpenDJ</code> instance.
 */
public class EmbeddedOpenDS {
    private static final String OPENDS_1x_VER = "5097";
    private static final String OPENDS_230B2_VER = "6500";
    private static final String OPENDS_UPGRADE_DIR = "/config/upgrade/";
    private static final String OPENDS_CONFIG_LDIF = "config.ldif";

    /** The embedded directory server. */
    private static EmbeddedDirectoryServer openDJ;
    /** The server root directory for the embedded server. */
    private static String serverRoot;
    /** The bind DN for the embedded server. */
    private static String bindDn;
    /** The connection parameters for the embedded server. */
    private static ConnectionParameters connectionParams;

    /**
     * Returns <code>true</code> if the server has already been started.
     *
     * @return <code>true</code> if the server has already been started.
     */
    public static boolean isStarted() {
        return openDJ.isRunning();
    }

    /**
     * Initializes to allow to perform a restricted set of operations on the embedded server.
     *
     * @param serverRoot
     *          The server root directory of the embedded directory server
     */
    public static void initialize(String serverRoot) {
        EmbeddedOpenDS.serverRoot = serverRoot;
        openDJ = manageEmbeddedDirectoryServerForRestrictedOps(configParams().serverRootDirectory(serverRoot));
    }

    /**
     * Sets up embedded OpenDJ during initial installation :
     * <ul>
     * <li>lays out the filesystem directory structure needed by OpenDJ
     * <li>sets up port numbers for ldap and replication
     * <li>invokes <code>EmbeddedUtils</code> to start the embedded server.
     * </ul>
     *
     * @param map        Map of properties collected by the configurator.
     * @param servletCtx Servlet Context to read deployed war contents.
     * @throws Exception on encountering errors.
     */
    public static void setup(Map<String, Object> map, ServletContext servletCtx) throws Exception {
        // Determine Cipher to be used
        SetupProgress.reportStart("emb.installingemb.null", null);
        String xform = getSupportedTransformation();

        if (xform == null) {
            SetupProgress.reportEnd("emb.noxform", null);
            throw new Exception("No transformation found");
        } else {
            map.put(OPENDS_TRANSFORMATION, xform);
            Object[] params = {xform};
            SetupProgress.reportEnd("emb.success.param", params);
        }

        String basedir = (String) map.get(SetupConstants.CONFIG_VAR_BASE_DIR);
        new File(basedir).mkdir();
        new File(serverRoot).mkdir();

        SetupProgress.reportStart("emb.opends.start", null);
        String zipFileName = "/WEB-INF/template/opendj/opendj.zip";
        BufferedInputStream bin = new BufferedInputStream(
                AMSetupUtils.getResourceAsStream(servletCtx, zipFileName), 10000);
        BufferedOutputStream bout = new BufferedOutputStream(
                new FileOutputStream(serverRoot + "/opendj.zip"), 10000);

        try {
            ByteStreams.copy(bin, bout);
        } catch (IOException ioe) {
            Debug.getInstance(SetupConstants.DEBUG_NAME).error(
                    "EmbeddedOpenDS.setup(): Error copying zip file", ioe);
            throw ioe;
        } finally {
            IOUtils.closeIfNotNull(bin);
            IOUtils.closeIfNotNull(bout);
        }

        bindDn = (String) map.get(CONFIG_VAR_DS_MGR_DN);
        connectionParams = connectionParams()
              .bindDn(bindDn)
              .bindPassword((String) map.get(CONFIG_VAR_DS_MGR_PWD))
              .hostName(getOpenDJHostName(map))
              .ldapPort(Integer.valueOf((String) map.get(CONFIG_VAR_DIRECTORY_SERVER_PORT)))
              .adminPort(Integer.valueOf((String) map.get(CONFIG_VAR_DIRECTORY_ADMIN_SERVER_PORT)))
              .adminUid("admin")
              .adminPassword((String) map.get(SetupConstants.CONFIG_VAR_DS_MGR_PWD));

        // It is valid to reinitialize the openDJ field with a complete configuration after having initialized
        // it partially without connection parameters in the first place
        openDJ = manageEmbeddedDirectoryServer(
            configParams()
              .serverRootDirectory(serverRoot),
            connectionParams,
            SetupProgress.getOutputStream(),
            SetupProgress.getOutputStream());

        extractOpenDJArchive();
        createTagSwappedFiles();
        removeOpenDJArchive();

        SetupProgress.reportEnd("emb.opends.stop", null);

        setupOpenDS(map);

        SetupProgress.reportStart("emb.installingemb", new Object[] {serverRoot});

        startServer();

        // Check: If adding a new server to a existing cluster
        if (!isMultiServer(map)) {
            // Default: single / first server.
            SetupProgress.reportStart("emb.creatingfamsuffix", null);
            try {
                loadLDIF(serverRoot + "/ldif/openam_suffix.ldif");
                SetupProgress.reportEnd("emb.creatingfamsuffix.success", null);
            } catch (EmbeddedDirectoryServerException ex) {
                SetupProgress.reportEnd("emb.creatingfamsuffix.failure", new Object[] { ex.getLocalizedMessage() });
                Debug.getInstance(SetupConstants.DEBUG_NAME).error(
                        "EmbeddedOpenDS.setupOpenDS. Error loading OpenAM suffix");
                throw new ConfiguratorException("emb.creatingfamsuffix.failure");
            }
        }
    }

    private static void extractOpenDJArchive() throws EmbeddedDirectoryServerException, IOException {
        File openDjZip = new File(serverRoot + "/opendj.zip");
        StaticUtils.extractZipArchive(openDjZip, new File(serverRoot),
                Collections.singletonList("bin"), Collections.<String>emptyList());
    }

    private static void createTagSwappedFiles() throws FileNotFoundException, IOException {
        String[] tagSwapFiles = {
                "template/ldif/openam_suffix.ldif.template"
        };

        for (String tagSwapFile : tagSwapFiles) {
            String fileIn = serverRoot + "/" + tagSwapFile;
            FileReader fin = new FileReader(fileIn);

            StringBuilder sbuf = new StringBuilder();
            char[] cbuf = new char[1024];
            int len;
            while ((len = fin.read(cbuf)) > 0) {
                sbuf.append(cbuf, 0, len);
            }
            FileWriter fout = null;

            try {
                fout = new FileWriter(serverRoot + "/" +
                        tagSwapFile.substring(0, tagSwapFile.indexOf(".template")));
                String inpStr = sbuf.toString();
                fout.write(ServicesDefaultValues.tagSwap(inpStr));
            } catch (IOException e) {
                Debug.getInstance(SetupConstants.DEBUG_NAME).error(
                        "EmbeddedOpenDS.setup(): Error tag swapping files", e);
                throw e;
            } finally {
                IOUtils.closeIfNotNull(fin);
                IOUtils.closeIfNotNull(fout);
            }
        }
    }

    private static void removeOpenDJArchive() {
        // remove zip
        File toDelete = new File(serverRoot + "/opendj.zip");
        if (!toDelete.delete()) {
            Debug.getInstance(SetupConstants.DEBUG_NAME).error(
                    "EmbeddedOpenDS.setup(): Unable to delete zip file:" +toDelete.getAbsolutePath());
        }
    }

    /**
     * Preferred transforms
     */
    final static String[] preferredTransforms =
            {
                    "RSA/ECB/OAEPWithSHA1AndMGF1Padding",      // Sun JCE
                    "RSA/ /OAEPPADDINGSHA-1",                  // IBMJCE
                    "RSA/ECB/OAEPWithSHA-1AndMGF-1Padding",    // BouncyCastle
                    "RSA/ECB/PKCS1Padding"                     // Fallback
            };
    final static String OPENDS_TRANSFORMATION = "OPENDS_TRANSFORMATION";

    /**
     * Traverses <code>preferredTransforms</code> list in order to
     * find a Cipher supported by underlying JCE providers.`
     *
     * @returns transformation available.
     */
    private static String getSupportedTransformation() {
        for (int i = 0; i < preferredTransforms.length; i++) {
            try {
                Cipher.getInstance(preferredTransforms[i]);
                return preferredTransforms[i];
            } catch (NoSuchAlgorithmException ex) {
            } catch (NoSuchPaddingException ex) {
            }
        }
        return null;
    }

    /**
     * Runs the OpenDJ setup command to create our instance
     *
     * @param conf The configuration options
     * @throws ConfiguratorException upon encountering errors.
     */
    private static void setupOpenDS(Map<String, Object> conf) throws Exception {
        SetupProgress.reportStart("emb.setupopends", null);
        try {
          openDJ.setup(SetupParameters.setupParams()
              .backendType("je")
              .baseDn((String) conf.get(CONFIG_VAR_ROOT_SUFFIX))
              .jmxPort(Integer.valueOf((String) conf.get(CONFIG_VAR_DIRECTORY_JMX_SERVER_PORT))));
        } catch (EmbeddedDirectoryServerException e) {
            SetupProgress.reportEnd("emb.setupopends.failed.param", new Object[] {e.getLocalizedMessage()});
            Debug.getInstance(SetupConstants.DEBUG_NAME).error("EmbeddedOpenDS.setupOpenDS. Error setting up OpenDS");
            throw new ConfiguratorException("configurator.embsetupopendsfailed");
        }
        SetupProgress.reportEnd("emb.setupopends.success", null);
        Debug.getInstance(SetupConstants.DEBUG_NAME).message("EmbeddedOpenDS.setupOpenDS: OpenDS setup succeeded.");
    }

    /**
     * Starts the embedded <code>OpenDJ</code> instance.
     *
     * @throws Exception upon encountering errors.
     */
    public static void startServer() throws Exception {
        if (isStarted()) {
            return;
        }
        Debug debug = Debug.getInstance(SetupConstants.DEBUG_NAME);
        debug.message("EmbeddedOpenDS.startServer(" + serverRoot + ")");
        debug.message("EmbeddedOpenDS.startServer:starting DS Server...");

        openDJ.start();

        debug.message("...EmbeddedOpenDS.startServer:DS Server started.");

        int sleepcount = 0;
        while (!openDJ.isRunning() && (sleepcount < 60)) {
            sleepcount++;
            SetupProgress.reportStart("emb.waitingforstarted", null);
            Thread.sleep(1000);
        }

        if (openDJ.isRunning()) {
            SetupProgress.reportEnd("emb.success", null);
        } else {
            SetupProgress.reportEnd("emb.failed", null);
        }

        ShutdownManager shutdownMan = com.sun.identity.common.ShutdownManager.getInstance();
        shutdownMan.addShutdownListener(new ShutdownListener() {
            public void shutdown() {
                try {
                    shutdownServer("Graceful Shutdown");
                } catch (Exception ex) {
                    Debug debug = Debug.getInstance(SetupConstants.DEBUG_NAME);
                    debug.error("EmbeddedOpenDS:shutdown hook failed", ex);
                }
            }
        }, ShutdownPriority.LOWEST);
    }


    /**
     * Gracefully shuts down the embedded OpenDJ instance.
     *
     * @param reason string representing reason why shutdown was called.
     * @throws Exception on encountering errors.
     */
    private static void shutdownServer(String reason) throws Exception {
        Debug debug = Debug.getInstance(SetupConstants.DEBUG_NAME);
        if (openDJ.isRunning()) {
            debug.message("EmbeddedOpenDS.shutdown server...");
            openDJ.stop("com.sun.identity.setup.EmbeddedOpenDS", LocalizableMessage.raw(reason));
            int sleepcount = 0;
            while (openDJ.isRunning() && (sleepcount < 60)) {
                sleepcount++;
                Thread.sleep(1000);
            }
            debug.message("EmbeddedOpenDS.shutdown server success.");
        }
    }

    public static void setupReplication(Map<String, Object> conf) throws Exception {
        SetupProgress.reportStart("emb.creatingreplica", null);
        ReplicationParameters replicationParams = getReplicationParameters(conf);
        Debug debug = Debug.getInstance(SetupConstants.DEBUG_NAME);
        if (debug.messageEnabled()) {
            debug.message("EmbeddedOpenDS.setupReplication: " + replicationParams.toString());
        }
        try {
            openDJ.configureReplication(replicationParams);
            openDJ.initializeReplication(replicationParams);
        } catch (EmbeddedDirectoryServerException e) {
            SetupProgress.reportEnd("emb.failed.param", new Object[] {e.getLocalizedMessage()});
            Debug.getInstance(SetupConstants.DEBUG_NAME).error(
                    "EmbeddedOpenDS.setupReplication. Error setting up replication");
            throw new ConfiguratorException("configurator.embreplfailed");
        }
        SetupProgress.reportEnd("emb.success", null);
        Debug.getInstance(SetupConstants.DEBUG_NAME).message(
                "EmbeddedOpenDS.setupReplication: replication setup succeeded.");
    }

    private static ReplicationParameters getReplicationParameters(Map<String, Object> conf) {
        return replicationParams()
                .baseDn((String) conf.get(CONFIG_VAR_ROOT_SUFFIX))
                .replicationPortSource(Integer.valueOf((String) conf.get(DS_EMB_REPL_REPLPORT1)))
                .replicationPortDestination(Integer.valueOf((String) conf.get(DS_EMB_REPL_REPLPORT2)))
                .connectionParamsForDestination(connectionParams()
                    .hostName((String) conf.get(DS_EMB_REPL_HOST2))
                    .adminPort(Integer.valueOf((String) conf.get(DS_EMB_REPL_ADMINPORT2))));
    }

    /**
     * Indicates if replication is running.
     *
     * @return {@code true} if replication is running, {@code false} otherwise
     */
    public static boolean isReplicationRunning() {
        // called by EmbeddedStatus with different parameters
        boolean isRunning = openDJ.isReplicationRunning();
        Debug debug = Debug.getInstance(SetupConstants.DEBUG_NAME);
        if (debug.messageEnabled()) {
            debug.message("EmbeddedOpenDS:isReplicationRunning: " + isRunning);
        }
        return isRunning;
    }

    /**
     * @return true if multi server option is selected in the configurator.
     */
    public static boolean isMultiServer(Map<String, Object> map) {
        String replFlag = (String) map.get(SetupConstants.DS_EMB_REPL_FLAG);
        if (replFlag != null && replFlag.startsWith(
                SetupConstants.DS_EMP_REPL_FLAG_VAL)) {
            return true;
        }
        return false;
    }

    /**
     * Loads LDIF data in the embedded instance.
     *
     * @param ldif  Full path of the ldif file to be loaded.
     */
    private static void loadLDIF(String ldif)
            throws EmbeddedDirectoryServerException {
        Debug debug = Debug.getInstance(SetupConstants.DEBUG_NAME);
        File ldifFile = new File(ldif);
        if (!ldifFile.exists()) {
            debug.error("LDIF File:" + ldifFile.getAbsolutePath() + " does not exist, unable to load!");
            throw new EmbeddedDirectoryServerException(
                    LocalizableMessage.raw("LDIF File:" + ldifFile.getAbsolutePath() + " does not exist"));
        }
        try {
            if (debug.messageEnabled()) {
                debug.message("EmbeddedOpenDS:loadLDIF(" + ldif + ")");
            }

            openDJ.importLDIF(importParams().backendId("userRoot").ldifFile(ldif));

            if (debug.messageEnabled()) {
                debug.message("EmbeddedOpenDS:loadLDIF Success");
            }
        } catch (EmbeddedDirectoryServerException ex) {
            debug.error("EmbeddedOpenDS:loadLDIF:ex=", ex);
            throw ex;
        }
    }

    /**
     * Returns a one-way hash for passwd using SSHA512 scheme.
     *
     * @param p Clear password string
     * @return hash value
     */
    public static String hash(String p) {
        String str = null;
        try {
            byte[] bb = p.getBytes();
            str = SaltedSHA512PasswordStorageScheme.encodeOffline(bb);
        } catch (Exception ex) {
            Debug debug = Debug.getInstance(SetupConstants.DEBUG_NAME);
            debug.error("EmbeddedOpenDS.hash failed : ex=" + ex);
        }
        return str;
    }

    public static List<Dn> findBackendsBaseDNs() throws Exception {
        final List<Dn> baseDNs = new LinkedList<>();
        RootCfgClient rootConfig = openDJ.getConfiguration().getRootConfiguration();
        for (String backend : rootConfig.listBackends()) {
            BackendCfgClient backendConfig = rootConfig.getBackend(backend);
            if (backendConfig instanceof PluggableBackendCfgClient) {
                PluggableBackendCfgClient config = (PluggableBackendCfgClient) backendConfig;
                baseDNs.addAll(config.getBaseDn());
            }
        }
        return baseDNs;
    }

    /**
     * Get the replication port of the directory server.
     */
    public static String getReplicationPort() {
        try {
            ReplicationServerCfgClient replicationServer = getReplicationSyncProviderConfig().getReplicationServer();
            return String.valueOf(replicationServer.getReplicationPort());
        } catch (Exception ex) {
            Debug.getInstance(SetupConstants.DEBUG_NAME)
                    .error("EmbeddedOpenDS.getReplicationPort(). Error getting replication port:", ex);
            return null;
        }
    }

    /**
     * Returns the admin port of the directory server.
     *
     * @return the admin port, or {@code null} if it can't be retrieved
     */
    public static String getAdminPort() {
        try {
            RootCfgClient rootConfig = openDJ.getConfiguration().getRootConfiguration();
            return String.valueOf(rootConfig.getAdministrationConnector().getListenPort());
        } catch (Exception e) {
            Debug.getInstance(SetupConstants.DEBUG_NAME)
                .error("EmbeddedOpenDS.getAdminPort(). Error getting admin port:", e);
            return null;
        }
    }

    /**
     * Returns a connection to the embedded server, authenticated using the directory manager DN provided
     * at setup.
     *
     * @return a connection to the directory server
     * @throws EmbeddedDirectoryServerException
     *          If the connection can't be established
     */
    public static Connection getConnection() throws EmbeddedDirectoryServerException {
        return openDJ.getInternalConnection();
    }

    /**
     * Synchronizes replication server and server domains with current list of OpenAM servers.
     *
     * @return {@code true} if synchronization succeeds, {@code false} otherwise
     */
    public static boolean syncReplicatedServers(Set<String> openAMServers) {
        Debug debug = Debug.getInstance(SetupConstants.DEBUG_NAME);
        debug.message("EmbeddedOPenDS:syncReplication:start processing.");

        final Set<String> knownReplicationServers = new HashSet<>();
        try {
            ReplicationServerCfgClient replServer = getReplicationSyncProviderConfig().getReplicationServer();
            knownReplicationServers.addAll(replServer.getReplicationServer());
        } catch (Exception e) {
            debug.message("EmbeddedOPenDS:syncReplication:read replication servers config failed.");
            return false;
        }
        Set<String> serversToKeep = new HashSet<>(knownReplicationServers);
        serversToKeep.retainAll(openAMServers);
        boolean needToRemoveServers = serversToKeep.size() != knownReplicationServers.size();

        if (needToRemoveServers) {
            if (debug.messageEnabled()) {
                debug.message("EmbeddedOpenDS:syncReplication:set replication servers to:" + serversToKeep);
            }
            try {
                // update replication server
                ReplicationSynchronizationProviderCfgClient syncProviderConfig = getReplicationSyncProviderConfig();
                ReplicationServerCfgClient replServer = syncProviderConfig.getReplicationServer();
                replServer.setReplicationServer(serversToKeep);
                replServer.commit();

                // update domains
                for (String domainName : syncProviderConfig.listReplicationDomains()) {
                    ReplicationDomainCfgClient domain = syncProviderConfig.getReplicationDomain(domainName);
                    domain.setReplicationServer(serversToKeep);
                    domain.commit();
                }

                // update
            } catch (Exception e) {
                debug.message("EmbeddedOPenDS:syncReplication:set replication servers failed.");
                return false;
            }
        }
        debug.message("EmbeddedOPenDS:syncReplication:end processing.");
        return true;
     }

    private static ReplicationSynchronizationProviderCfgClient getReplicationSyncProviderConfig() throws Exception {
        RootCfgClient rootConfig = openDJ.getConfiguration().getRootConfiguration();
        return (ReplicationSynchronizationProviderCfgClient) rootConfig
                    .getSynchronizationProvider("cn=Multimaster Synchronization");
    }

    /**
     * Synchronizes replication domain info with current list of OpenAM servers.
     */
    public static boolean syncReplicatedServerList(Set currServerSet) {
        try (Connection conn = openDJ.getInternalConnection(Dn.valueOf(bindDn))) {
            Set<String> dsServers = getReplicatedServers(conn);

            if (dsServers == null) {
                return false;
            }
            for (String tok : dsServers) {
                if (!currServerSet.contains(tok)) {
                    removeServerFromReplication(conn, tok);
                }
            }
        } catch (Exception ex) {
            return false;
        }
        return true;
    }

    private static final String ALL_SERVERS_DN = "cn=all-servers,cn=Server Groups,cn=admin data";

    /**
     * Removes host:port from OpenDJ replication
     */
    private static void removeServerFromReplication(Connection conn, String serverToRemove) {
        String replServerDN = "cn=" + serverToRemove + ",cn=Servers,cn=admin data";
        final String[] attrs = {"ds-cfg-key-id"};
        Debug debug = Debug.getInstance(SetupConstants.DEBUG_NAME);
        if (conn == null) {
            debug.error("EmbeddedOpenDS:syncOpenDSServer():" +
                    "Could not connect to local OpenDJ instance." + replServerDN);
            return;
        }
        String trustKey = null;
        try {
            SearchResultEntry entry =
                    conn.searchSingleEntry(LDAPRequests.newSingleEntrySearchRequest(replServerDN, attrs));
            if (entry != null) {
                Attribute attr = entry.getAttribute(attrs[0]);
                if (attr != null) {
                    trustKey = attr.firstValueAsString();
                }
                String keyDN = "ds-cfg-key-id=" + trustKey +
                        ",cn=instance keys,cn=admin data";
                conn.delete(LDAPRequests.newDeleteRequest(keyDN));
            } else {
                debug.error("EmbeddedOpenDS:syncOpenDSServer():" +
                        "Could not find trustkey for:" + replServerDN);
            }
        } catch (Exception ex) {
            debug.error("EmbeddedOpenDS.syncOpenDSServer()." +
                    " Error getting replication key:", ex);

        }
        try {
            conn.delete(LDAPRequests.newDeleteRequest(replServerDN));
        } catch (Exception ex) {
            debug.error("EmbeddedOpenDS.syncOpenDSServer()." +
                    " Error getting deleting server entry:" + replServerDN, ex);
        }
        try {
            ModifyRequest modifyRequest = LDAPRequests.newModifyRequest(ALL_SERVERS_DN)
                    .addModification(new Modification(ModificationType.DELETE,
                            Attributes.singletonAttribute("uniqueMember", "cn=" + serverToRemove)));
            conn.modify(modifyRequest);
        } catch (Exception ex) {
            debug.error("EmbeddedOpenDS.syncOpenDSServer()." +
                    " Error getting removing :" + ALL_SERVERS_DN, ex);

        }
    }

    /**
     * Gets list of replicated servers from local OpenDJ directory.
     */
    private static Set<String> getReplicatedServers(Connection conn) {
        final String[] attrs = {"uniqueMember"};
        Debug debug = Debug.getInstance(SetupConstants.DEBUG_NAME);
        try {
            if (conn != null) {
                SearchResultEntry entry = conn.searchSingleEntry(newSingleEntrySearchRequest(ALL_SERVERS_DN, attrs));
                if (entry != null) {
                    Set<String> hostSet = new HashSet<>();
                    Attribute attr = entry.getAttribute(attrs[0]);
                    if (attr != null) {
                        for (ByteString value : attr) {
                            hostSet.add(value.toString().substring(3, value.length()));
                        }
                    }
                    return hostSet;
                } else {
                    debug.error("EmbeddedOpenDS:syncOpenDSServer():" +
                            "Could not find trustkey for:" + ALL_SERVERS_DN);
                }
            } else {
                debug.error("EmbeddedOpenDS:syncOpenDSServer():" +
                        "Could not connect to local opends instance.");
            }
        } catch (Exception ex) {
            debug.error("EmbeddedOpenDS.syncOpenDSServer()." +
                    " Error getting replication key:", ex);

        }
        return null;
    }


    /**
     * Rebuilds SMS indexes for the embedded DJ config store.
     *
     * @return the status code.
     */
    public static void rebuildSMSIndex() throws EmbeddedDirectoryServerException {
        rebuildIndex(SMSEntry.getRootSuffix());
    }

    /**
     * Rebuilds indexes for the given base DN and installation directory.
     *
     * @param baseDN the base DN to rebuild indexes for.
     * @return the status code
     */
    public static void rebuildIndex(String baseDN) throws EmbeddedDirectoryServerException {
        Map<String, Object> rebuildIndexData = new HashMap<String, Object>(1);
        rebuildIndexData.put(SetupConstants.CONFIG_VAR_ROOT_SUFFIX, baseDN);
        rebuildIndex(rebuildIndexData);
    }

    /**
     * Rebuild indexes using the provided configuration map.
     *
     * @param map
     *            contains the base DN to rebuild indexes for.
     * @throws Exception
     */
    public static void rebuildIndex(Map<String, Object> map) throws EmbeddedDirectoryServerException {
        boolean serverWasRunning = openDJ.isRunning();
        if (serverWasRunning) {
            openDJ.stop(EmbeddedOpenDS.class.getName(),
                    LocalizableMessage.raw("Shutting down server before rebuildIndex"));
        }
        Debug debug = Debug.getInstance(SetupConstants.DEBUG_NAME);
        try {
            openDJ.rebuildIndex(
                    rebuildIndexParams().baseDN((String) map.get(SetupConstants.CONFIG_VAR_ROOT_SUFFIX)));
        } catch (EmbeddedDirectoryServerException ex) {
            debug.error("EmbeddedOpenDS:rebuildIndex:error=" + ex.getLocalizedMessage());
            throw ex;
        }
        if (debug.messageEnabled()) {
            debug.message("EmbeddedOpenDS:rebuildIndex:rebuild complete");
        }
        if (serverWasRunning) {
            openDJ.start();
        }
    }

    /**
     * @return true if installed OpenDS is version 1.0.2
     */
    public static boolean isOpenDSVer1Installed() {
        boolean openDSVer1x = false;

        if (getOpenDSVersion().equals(OPENDS_1x_VER)) {
            openDSVer1x = true;
        }

        return openDSVer1x;
    }

    /**
     * @return true if installed OpenDS is version 2.3.0BACKPORT2
     */
    public static boolean isOpenDSVer230Installed() {
        boolean openDSVer230b2 = false;

        if (getOpenDSVersion().equals(OPENDS_230B2_VER)) {
            openDSVer230b2 = true;
        }

        return openDSVer230b2;
    }

    private static String getOpenDSVersion() {
        Debug debug = Debug.getInstance(SetupConstants.DEBUG_NAME);
        String odsRoot = AMSetupServlet.getBaseDir() + "/" + SetupConstants.SMS_OPENDS_DATASTORE;
        File configLdif = new File(odsRoot + OPENDS_UPGRADE_DIR);
        File buildInfo = new File(odsRoot + "/" + "config" + "/" + SetupConstants.OPENDJ_BUILDINFO);
        String version = "unknown";
        if (configLdif.exists() && configLdif.isDirectory()) {
            String[] configFile = configLdif.list(new FilenameFilter() {
                //@Override -- Not Allowed Here.
                public boolean accept(File dir, String name) {
                    return name.startsWith(OPENDS_CONFIG_LDIF);
                }
            });

            if (configFile.length != 0) {
                version = configFile[0].substring(configFile[0].lastIndexOf('.') + 1);
            } else {
                debug.error("Unable to determine OpenDJ version");
            }
        } else if (buildInfo.exists() && buildInfo.canRead() && buildInfo.isFile()) {
            try {
                version = openDJ.getBuildVersion();
            } catch (EmbeddedDirectoryServerException ex) {
                debug.error("Unable to determine OpenDJ version: " + ex.getLocalizedMessage());
            }
        } else if (debug.warningEnabled()) {
            debug.warning("Unable to determine OpenDJ version; could be pre-config");
        }

        if (debug.messageEnabled()) {
            debug.message("Found OpenDJ version: " + version);
        }

        return version;
    }

    /** Returns the host name for the embedded OpenDJ. */
    private static String getOpenDJHostName(Map<String, Object> configProperties) {
        String dirHost = (String) configProperties
                .get(SetupConstants.CONFIG_VAR_DIRECTORY_SERVER_HOST);
        if (dirHost.equals("localhost")) {
            dirHost = (String) configProperties
                    .get(SetupConstants.CONFIG_VAR_SERVER_HOST);
        }
        return dirHost;
    }
}
