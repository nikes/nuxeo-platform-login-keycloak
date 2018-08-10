package org.nuxeo.ecm.platform.ui.web.keycloak;

import java.io.Serializable;
import java.util.*;

import org.nuxeo.ecm.core.api.*;
import org.nuxeo.ecm.platform.usermanager.UserManager;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.usermapper.extension.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plugin for the UserMapper to manage mapping between Ketcloack user and Nuxeo counterpart
 *
 * @since 10.2
 */
public class KeycloakUserMapper implements UserMapper {
    private static final Logger log = LoggerFactory.getLogger(KeycloakUserMapper.class);

    private UserManager userManager;

    @Override
    public void init(Map<String, String> map) throws Exception {
        userManager = Framework.getService(UserManager.class);
    }

    @Override
    public NuxeoPrincipal getOrCreateAndUpdateNuxeoPrincipal(Object userObject) {
        return getOrCreateAndUpdateNuxeoPrincipal(userObject, true, true, null);
    }

    @Override
    public NuxeoPrincipal getOrCreateAndUpdateNuxeoPrincipal(Object userObject, boolean createIfNeeded, boolean update,
                                                             Map<String, Serializable> params) {
        DocumentModel userModel = null;

        Map<String, Serializable> searchAttributes = new HashMap<>();
        Map<String, Serializable> userAttributes = new HashMap<>();

        if (params != null) {
            searchAttributes.putAll(params);
        }

        resolveAttributes(userObject, searchAttributes, userAttributes);

        final String username = (String) searchAttributes.get(userManager.getUserIdField());

        if (username != null && !username.isEmpty()) {
            userModel = Framework.doPrivileged(() -> userManager.getUserModel(username));
        }
        if (userModel == null) {
            DocumentModelList userDocs = Framework.doPrivileged(() -> userManager.searchUsers(searchAttributes, Collections.emptySet()));
            if (userDocs.size() > 1) {
                log.warn("Can not map user with filter " + searchAttributes.toString() + " : too many results");
            }
            if (userDocs.size() == 1) {
                userModel = userDocs.get(0);
            }
        }
        if (userModel != null) {
            if (update) {
                updatePrincipal(userAttributes, userModel);
            }
        } else {
            if (!createIfNeeded) {
                return null;
            }
            for (String k : searchAttributes.keySet()) {
                if (!userAttributes.containsKey(k)) {
                    userAttributes.put(k, searchAttributes.get(k));
                }
            }
            userModel = createPrincipal(userAttributes);
        }

        attachGroups(userObject);

        if (userModel != null) {
            return userManager.getPrincipal(username);
        }
        return null;
    }

    @Override
    public Object wrapNuxeoPrincipal(NuxeoPrincipal principal, Object nativePrincipal, Map<String, Serializable> params) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void release() {
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private DocumentModel createPrincipal(Map<String, Serializable> attributes) {
        DocumentModel userModel = userManager.getBareUserModel();
        for (String property : attributes.keySet()) {
            userModel.setProperty(userManager.getUserSchemaName(), property, attributes.get(property));
        }
        return Framework.doPrivileged(() -> userManager.createUser(userModel));
    }

    private void updatePrincipal(Map<String, Serializable> attributes, DocumentModel userModel) {
        for (String property : attributes.keySet()) {
            userModel.setProperty(userManager.getUserSchemaName(), property, attributes.get(property));
        }
        Framework.doPrivileged(() -> userManager.updateUser(userModel));
    }

    /**
     * Attach user to groups
     */
    private void attachGroups(Object userObject) {
        KeycloakUserInfo userInfo = (KeycloakUserInfo) userObject;
        String username = userInfo.getUserName();

        for (String role : userInfo.getRoles()) {
            DocumentModel group = findGroup(role);
            if (group != null) {
                List<String> users = Framework.doPrivileged(() -> userManager.getUsersInGroupAndSubGroups(role));
                if (!users.contains(role)) {
                    users.add(username);

                    group.setProperty(userManager.getGroupSchemaName(), userManager.getGroupMembersField(), users);
                    Framework.doPrivileged(() -> userManager.updateGroup(group));
                }
            } else {
                // TODO We can create group here, but its need now?
            }
        }
    }

    private DocumentModel findGroup(String role) {
        Map<String, Serializable> query = new HashMap<>();
        query.put(userManager.getGroupIdField(), role);
        DocumentModelList groups = Framework.doPrivileged(() -> userManager.searchGroups(query, Collections.emptySet()));

        if (groups.isEmpty()) {
            return null;
        }
        return groups.get(0);
    }

    private void resolveAttributes(Object userObject, Map<String, Serializable> searchAttributes,
                                   Map<String, Serializable> userAttributes) {
        KeycloakUserInfo userInfo = (KeycloakUserInfo) userObject;

        searchAttributes.put(userManager.getUserIdField(), userInfo.getUserName());

        userAttributes.put(userManager.getUserIdField(), userInfo.getUserName());
        userAttributes.put(userManager.getUserEmailField(), userInfo.getEmail());
        userAttributes.put("firstName", userInfo.getFirstName());
        userAttributes.put("lastName", userInfo.getLastName());
        userAttributes.put("password", userInfo.getPassword());
        userAttributes.put("company", userInfo.getCompany());
    }
}
