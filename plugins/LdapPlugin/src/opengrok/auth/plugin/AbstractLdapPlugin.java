/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License (the "License").
 * You may not use this file except in compliance with the License.
 *
 * See LICENSE.txt included in this distribution for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at LICENSE.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information: Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 */

 /*
 * Copyright (c) 2017, Oracle and/or its affiliates. All rights reserved.
 */
package opengrok.auth.plugin;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.servlet.http.HttpServletRequest;
import opengrok.auth.plugin.configuration.Configuration;
import opengrok.auth.plugin.entity.User;
import opengrok.auth.plugin.ldap.AbstractLdapProvider;
import opengrok.auth.plugin.ldap.FakeLdapFacade;
import opengrok.auth.plugin.ldap.LdapFacade;
import org.opensolaris.opengrok.authorization.IAuthorizationPlugin;
import org.opensolaris.opengrok.configuration.Group;
import org.opensolaris.opengrok.configuration.Project;
import org.opensolaris.opengrok.configuration.RuntimeEnvironment;

/**
 * Abstract class for all plugins working with LDAP. Takes care of
 * <ul>
 * <li>controlling the established session</li>
 * <li>controlling if the session belongs to the user</li>
 * <li>controlling plugin version</li>
 * </ul>
 *
 * <p>
 * The intended methods to implement are the and {@link #checkEntity(HttpServletRequest, Project)
 * {@link #checkEntity(HttpServletRequest, Group)}.
 * </p>
 *
 * @author Krystof Tulinger
 */
abstract public class AbstractLdapPlugin implements IAuthorizationPlugin {

    /**
     * This is used to ensure that every instance of this plugin has its own
     * unique name for its session parameters.
     */
    public static long nextId = 1;

    private static final String CONFIGURATION_PARAM = "configuration";
    protected static final String FAKE_PARAM = "fake";

    protected String SESSION_USERNAME = "opengrok-group-plugin-username";
    protected String SESSION_ESTABLISHED = "opengrok-group-plugin-session-established";
    protected String SESSION_VERSION = "opengrok-group-plugin-session-version";

    /**
     * Configuration for the LDAP servers.
     */
    private Configuration cfg;

    /**
     * Map of currently used configurations.<br>
     * file path => object.
     */
    private static final Map<String, Configuration> LOADED_CONFIGURATIONS = new ConcurrentHashMap<>();

    /**
     * LDAP lookup facade.
     */
    private AbstractLdapProvider ldap;

    public AbstractLdapPlugin() {
        SESSION_USERNAME += "-" + nextId;
        SESSION_ESTABLISHED += "-" + nextId;
        SESSION_VERSION += "-" + nextId;
        nextId++;
    }

    /**
     * Fill the session with some information related to the subclass.
     *
     * @param req the current request
     * @param user user decoded from the headers
     */
    abstract public void fillSession(HttpServletRequest req, User user);

    /**
     * Decide if the project should be allowed for this request.
     *
     * @param request the request
     * @param project the project
     * @return true if yes; false otherwise
     */
    abstract public boolean checkEntity(HttpServletRequest request, Project project);

    /**
     * Decide if the group should be allowed for this request.
     *
     * @param request the request
     * @param group the group
     * @return true if yes; false otherwise
     */
    abstract public boolean checkEntity(HttpServletRequest request, Group group);

    /**
     * Loads the configuration into memory.
     */
    @Override
    public void load(Map<String, Object> parameters) {
        Boolean fake;
        String configurationPath;

        if ((fake = (Boolean) parameters.get(FAKE_PARAM)) != null
                && fake) {
            ldap = new FakeLdapFacade();
            return;
        }
        
        if ((configurationPath = (String) parameters.get(CONFIGURATION_PARAM)) == null) {
            throw new NullPointerException("Missing param [" + CONFIGURATION_PARAM + "]");
        }

        try {
            cfg = getConfiguration(configurationPath);
            ldap = new LdapFacade(cfg);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to read the configuration", ex);
        }
    }

    /**
     * Return the configuration for the given path. If the configuration is
     * already loaded, use that one. Otherwise try to load the file into the
     * configuration.
     *
     * @param configurationPath the path to the file with the configuration
     * @return the object (new or from cache)
     * @throws IOException when any IO error occurs
     */
    protected Configuration getConfiguration(String configurationPath) throws IOException {
        if ((cfg = LOADED_CONFIGURATIONS.get(configurationPath)) == null) {
            LOADED_CONFIGURATIONS.put(configurationPath, cfg = Configuration.read(new File(configurationPath)));
        }
        return cfg;
    }

    /**
     * Closes the LDAP connections.
     */
    @Override
    public void unload() {
        if (ldap != null) {
            ldap.close();
            ldap = null;
        }
        cfg = null;
    }

    /**
     * Return the configuration object.
     *
     * @return the configuration
     */
    public Configuration getConfiguration() {
        return cfg;
    }

    /**
     * Return the LDAP provider.
     *
     * @return the LDAP provider
     */
    public AbstractLdapProvider getLdapProvider() {
        return ldap;
    }

    /**
     * Check if the session user corresponds to the authenticated user.
     *
     * @param sessionUsername user from the session
     * @param authUser user from the request
     * @return true if it does; false otherwise
     */
    protected boolean isSameUser(String sessionUsername, String authUser) {
        if (sessionUsername != null
                && sessionUsername.equals(authUser)) {
            return true;
        }
        return false;
    }

    /**
     * Check if the session exists and contains all necessary fields required by
     * this plugin.
     *
     * @param req the HTTP request
     * @return true if it does; false otherwise
     */
    protected boolean sessionExists(HttpServletRequest req) {
        return req != null && req.getSession() != null
                && req.getSession().getAttribute(SESSION_ESTABLISHED) != null
                && req.getSession().getAttribute(SESSION_VERSION) != null
                && req.getSession().getAttribute(SESSION_USERNAME) != null;
    }

    /**
     * Ensures that after the call the session for the user will be created with
     * appropriate fields. If any error occurs during the call which might be:
     * <ul>
     * <li>The user has not been authenticated</li>
     * <li>The user can not be retrieved from LDAP</li>
     * <li>There are no records for authorization for the user</li>
     * </ul>
     * the session is established as an empty session to avoid any exception in
     * the caller.
     *
     * @param req the http request
     */
    @SuppressWarnings("unchecked")
    private void ensureSessionExists(HttpServletRequest req) {
        User user;
        if (req.getSession() == null) {
            // old/invalid request (should not happen)
            return;
        }

        if ((user = (User) req.getAttribute(UserPlugin.REQUEST_ATTR)) == null) {
            updateSession(req, null, false, getPluginVersion());
            return;
        }

        if (sessionExists(req)
                // we've already filled the groups and projects
                && (boolean) req.getSession().getAttribute(SESSION_ESTABLISHED)
                // the session belongs to the user from the request
                && isSameUser((String) req.getSession().getAttribute(SESSION_USERNAME), user.getUsername())
                // and this is not a case when we want to renew all sessions
                && !isSessionInvalidated(req)) {
            /**
             * The session is already filled so no need to
             * {@link #updateSession()}
             */
            return;
        }

        updateSession(req, user.getUsername(), false, getPluginVersion());

        if (ldap == null) {
            return;
        }

        fillSession(req, user);

        updateSession(req, user.getUsername(), true, getPluginVersion());
    }

    /**
     * Fill the session with new values.
     *
     * @param req the request
     * @param username new username
     * @param established new value for established
     * @param sessionV new value for session version
     */
    protected void updateSession(HttpServletRequest req,
            String username,
            boolean established,
            int sessionV) {
        setSessionEstablished(req, established);
        setSessionUsername(req, username);
        setSessionVersion(req, sessionV);
    }

    /**
     * Is this session marked as invalid?
     *
     * @param req the request
     * @return true if it is; false otherwise
     */
    protected boolean isSessionInvalidated(HttpServletRequest req) {
        Integer version;
        if ((version = (Integer) req.getAttribute(SESSION_VERSION)) != null) {
            return version != getPluginVersion();
        }
        if ((version = (Integer) req.getSession().getAttribute(SESSION_VERSION)) != null) {
            req.setAttribute(SESSION_VERSION, version);
            return version != getPluginVersion();
        }
        return true;
    }

    /**
     * Set session version into the session.
     *
     * @param req request containing the session
     * @param value the value
     */
    protected void setSessionVersion(HttpServletRequest req, Integer value) {
        req.getSession().setAttribute(SESSION_VERSION, value);
        req.setAttribute(SESSION_VERSION, value);
    }

    /**
     * Set session established flag into the session.
     *
     * @param req request containing the session
     * @param value the value
     */
    protected void setSessionEstablished(HttpServletRequest req, Boolean value) {
        req.getSession().setAttribute(SESSION_ESTABLISHED, value);
    }

    /**
     * Set session username for the user.
     *
     * @param req request containing the session
     * @param value the value
     */
    protected void setSessionUsername(HttpServletRequest req, String value) {
        req.getSession().setAttribute(SESSION_USERNAME, value);
    }

    /**
     * Return the current plugin version tracked by the authorization framework.
     *
     * @return the version
     */
    protected static int getPluginVersion() {
        return RuntimeEnvironment.getInstance().getPluginVersion();
    }

    @Override
    public boolean isAllowed(HttpServletRequest request, Project project) {
        ensureSessionExists(request);

        if (request.getSession() == null) {
            return false;
        }

        return checkEntity(request, project);
    }

    @Override
    public boolean isAllowed(HttpServletRequest request, Group group) {
        ensureSessionExists(request);

        if (request.getSession() == null) {
            return false;
        }

        return checkEntity(request, group);
    }
}
