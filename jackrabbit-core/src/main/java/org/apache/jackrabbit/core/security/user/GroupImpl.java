/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.core.security.user;

import org.apache.jackrabbit.JcrConstants;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.jackrabbit.commons.flat.BTreeManager;
import org.apache.jackrabbit.commons.flat.ItemSequence;
import org.apache.jackrabbit.commons.flat.PropertySequence;
import org.apache.jackrabbit.commons.flat.Rank;
import org.apache.jackrabbit.commons.flat.TreeManager;
import org.apache.jackrabbit.core.NodeImpl;
import org.apache.jackrabbit.core.PropertyImpl;
import org.apache.jackrabbit.util.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * GroupImpl...
 */
class GroupImpl extends AuthorizableImpl implements Group {

    private static final Logger log = LoggerFactory.getLogger(GroupImpl.class);

    private Principal principal;

    protected GroupImpl(NodeImpl node, UserManagerImpl userManager) {
        super(node, userManager);
    }

    //-------------------------------------------------------< Authorizable >---

    /**
     * @see Authorizable#isGroup()
     */
    public boolean isGroup() {
        return true;
    }

    /**
     * @see Authorizable#getPrincipal()
     */
    public Principal getPrincipal() throws RepositoryException {
        if (principal == null) {
            principal = new NodeBasedGroup(getPrincipalName());
        }
        return principal;
    }

    //--------------------------------------------------------------< Group >---

    /**
     * @see Group#getDeclaredMembers()
     */
    public Iterator<Authorizable> getDeclaredMembers() throws RepositoryException {
        return getMembers(false, UserManager.SEARCH_TYPE_AUTHORIZABLE).iterator();
    }

    /**
     * @see Group#getMembers()
     */
    public Iterator<Authorizable> getMembers() throws RepositoryException {
        return getMembers(true, UserManager.SEARCH_TYPE_AUTHORIZABLE).iterator();
    }

    /**
     * @see Group#isMember(Authorizable)
     */
    public boolean isMember(Authorizable authorizable) throws RepositoryException {
        if (authorizable == null || !(authorizable instanceof AuthorizableImpl)
                || getNode().isSame(((AuthorizableImpl) authorizable).getNode())) {
            return false;
        } else {
            String thisID = getID();
            AuthorizableImpl impl = (AuthorizableImpl) authorizable;
            for (Iterator<Group> it = impl.memberOf(); it.hasNext();) {
                if (thisID.equals(it.next().getID())) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * @see Group#addMember(Authorizable)
     */
    public boolean addMember(Authorizable authorizable) throws RepositoryException {
        if (!(authorizable instanceof AuthorizableImpl)) {
            log.warn("Invalid Authorizable: {}", authorizable);
            return false;
        }

        AuthorizableImpl authImpl = ((AuthorizableImpl) authorizable);
        Node memberNode = authImpl.getNode();
        if (memberNode.isSame(getNode())) {
            String msg = "Attempt to add a group as member of itself (" + getID() + ").";
            log.warn(msg);
            return false;
        }

        if (isCyclicMembership(authImpl)) {
            log.warn("Attempt to create circular group membership.");
            return false;
        }

        return getMembershipProvider(getNode()).addMember(authImpl);
    }


    /**
     * @see Group#removeMember(Authorizable)
     */
    public boolean removeMember(Authorizable authorizable) throws RepositoryException {
        if (!(authorizable instanceof AuthorizableImpl)) {
            log.warn("Invalid Authorizable: {}", authorizable);
            return false;
        }

        return getMembershipProvider(getNode()).removeMember((AuthorizableImpl) authorizable);
    }

    //--------------------------------------------------------------------------

    private MembershipProvider getMembershipProvider(NodeImpl node) throws RepositoryException {
        MembershipProvider msp;
        if (userManager.getGroupMembershipSplitSize() > 0) {
            if (node.hasNode(N_MEMBERS) || !node.hasProperty(P_MEMBERS)) {
                msp = new NodeBasedMembershipProvider(node);
            } else {
                msp = new PropertyBasedMembershipProvider(node);
            }
        } else {
            if (node.hasProperty(P_MEMBERS) || !node.hasNode(N_MEMBERS)) {
                msp = new PropertyBasedMembershipProvider(node);
            } else {
                msp = new NodeBasedMembershipProvider(node);
            }
        }

        if (node.hasProperty(P_MEMBERS) && node.hasNode(N_MEMBERS)) {
            log.warn("Found members node and members property on node {}. Ignoring {} members", node,
                    userManager.getGroupMembershipSplitSize() > 0 ? "property" : "node");
        }

        return msp;
    }

    /**
     * @param includeIndirect If <code>true</code> all members of this group
     *                        will be return; otherwise only the declared members.
     * @param type            Any of {@link UserManager#SEARCH_TYPE_AUTHORIZABLE},
     *                        {@link UserManager#SEARCH_TYPE_GROUP}, {@link UserManager#SEARCH_TYPE_USER}.
     * @return A collection of members of this group.
     * @throws RepositoryException If an error occurs while collecting the members.
     */
    private Collection<Authorizable> getMembers(boolean includeIndirect, int type) throws RepositoryException {
        return getMembershipProvider(getNode()).getMembers(includeIndirect, type);
    }

    /**
     * Returns <code>true</code> if the given <code>newMember</code> is a Group
     * and contains <code>this</code> Group as declared or inherited member.
     *
     * @param newMember The new member to be tested for cyclic membership.
     * @return true if the 'newMember' is a group and 'this' is an declared or
     *         inherited member of it.
     * @throws javax.jcr.RepositoryException If an error occurs.
     */
    private boolean isCyclicMembership(AuthorizableImpl newMember) throws RepositoryException {
        if (newMember.isGroup()) {
            GroupImpl gr = (GroupImpl) newMember;
            for (Authorizable member : gr.getMembers(true, UserManager.SEARCH_TYPE_GROUP)) {
                GroupImpl grMemberImpl = (GroupImpl) member;
                if (getNode().getUUID().equals(grMemberImpl.getNode().getUUID())) {
                    // found cyclic group membership
                    return true;
                }

            }
        }
        return false;
    }

    private PropertySequence getPropertySequence(Node nMembers) throws RepositoryException {
        Comparator<String> order = Rank.comparableComparator();
        int maxChildren = userManager.getGroupMembershipSplitSize();
        int minChildren = maxChildren / 2;

        TreeManager treeManager = new BTreeManager(nMembers, minChildren, maxChildren, order,
                userManager.isAutoSave());

        treeManager.getIgnoredProperties().addAll(Arrays.asList(
                JcrConstants.JCR_CREATED,
                "jcr:createdBy"));

        return ItemSequence.createPropertySequence(treeManager);
    }

    private void collectMembers(Value memberRef, Collection<Authorizable> members, boolean includeIndirect,
                                int type) throws RepositoryException {

        try {
            NodeImpl member = (NodeImpl) getSession().getNodeByIdentifier(memberRef.getString());
            if (member.isNodeType(NT_REP_GROUP)) {
                if (type != UserManager.SEARCH_TYPE_USER) {
                    Group group = userManager.createGroup(member);
                    // only retrieve indirect group-members if the group is not
                    // yet present (detected eventual circular membership).
                    if (members.add(group) && includeIndirect) {
                        members.addAll(((GroupImpl) group).getMembers(true, type));
                    }
                } // else: groups are ignored
            } else if (member.isNodeType(NT_REP_USER)) {
                if (type != UserManager.SEARCH_TYPE_GROUP) {
                    User user = userManager.createUser(member);
                    members.add(user);
                }
            } else {
                // reference does point to an authorizable node -> not a
                // member of this group -> ignore
                log.debug("Group member entry with invalid node type {} -> " +
                        "Not included in member set.", member.getPrimaryNodeType().getName());
            }
        } catch (ItemNotFoundException e) {
            // dangling weak reference -> clean upon next write.
            log.debug("Authorizable node referenced by {} doesn't exist any more -> " +
                    "Ignored from member list.", getID());
        }
    }

    //------------------------------------------------------< inner classes >---

    private class NodeBasedGroup extends NodeBasedPrincipal implements java.security.acl.Group {

        private Set<Principal> members;

        private NodeBasedGroup(String name) {
            super(name);
        }

        //----------------------------------------------------------< Group >---

        /**
         * @return Always <code>false</code>. Group membership must be edited
         *         using the enclosing <code>GroupImpl</code> object.
         * @see java.security.acl.Group#addMember(Principal)
         */
        public boolean addMember(Principal user) {
            return false;
        }

        /**
         * Returns true, if the given <code>Principal</code> is represented by
         * a Authorizable, that is a member of the underlying UserGroup.
         *
         * @see java.security.acl.Group#isMember(Principal)
         */
        public boolean isMember(Principal member) {
            Collection<Principal> members = getMembers();
            if (members.contains(member)) {
                // shortcut.
                return true;
            }

            // test if member of a member-group
            for (Principal p : members) {
                if (p instanceof java.security.acl.Group &&
                        ((java.security.acl.Group) p).isMember(member)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * @return Always <code>false</code>. Group membership must be edited
         *         using the enclosing <code>GroupImpl</code> object.
         * @see java.security.acl.Group#isMember(Principal)
         */
        public boolean removeMember(Principal user) {
            return false;
        }

        /**
         * Return all principals that refer to every member of the underlying
         * user group.
         *
         * @see java.security.acl.Group#members()
         */
        public Enumeration<? extends Principal> members() {
            return Collections.enumeration(getMembers());
        }

        //---------------------------------------------------< Serializable >---

        /**
         * implement the writeObject method to assert initalization of all members
         * before serialization.
         *
         * @param stream The object output stream.
         * @throws IOException If an error occurs.
         */
        private void writeObject(ObjectOutputStream stream) throws IOException {
            getMembers();
            stream.defaultWriteObject();
        }

        //----------------------------------------------------------------------

        private Collection<Principal> getMembers() {
            if (members == null) {
                members = new HashSet<Principal>();
                try {
                    for (Iterator<Authorizable> it = GroupImpl.this.getMembers(); it.hasNext();) {
                        members.add(it.next().getPrincipal());
                    }
                } catch (RepositoryException e) {
                    // should not occur.
                    log.error("Unable to retrieve Group members.");
                }
            }
            return members;
        }
    }

    private interface MembershipProvider {
        boolean addMember(AuthorizableImpl authorizable) throws RepositoryException;

        boolean removeMember(AuthorizableImpl authorizable) throws RepositoryException;

        Collection<Authorizable> getMembers(boolean includeIndirect, int type) throws RepositoryException;
    }

    private class PropertyBasedMembershipProvider implements MembershipProvider {
        private final NodeImpl node;

        public PropertyBasedMembershipProvider(NodeImpl node) {
            super();
            this.node = node;
        }

        public boolean addMember(AuthorizableImpl authorizable) throws RepositoryException {
            Node memberNode = authorizable.getNode();

            Value[] values;
            Value toAdd = getSession().getValueFactory().createValue(memberNode, true);
            if (node.hasProperty(P_MEMBERS)) {
                Value[] old = node.getProperty(P_MEMBERS).getValues();
                for (Value v : old) {
                    if (v.equals(toAdd)) {
                        log.debug("Authorizable {} is already member of {}", authorizable, this);
                        return false;
                    }
                }

                values = new Value[old.length + 1];
                System.arraycopy(old, 0, values, 0, old.length);
            } else {
                values = new Value[1];
            }
            values[values.length - 1] = toAdd;

            userManager.setProtectedProperty(node, P_MEMBERS, values, PropertyType.WEAKREFERENCE);
            userManager.getMembershipCache().clear();
            return true;
        }

        public boolean removeMember(AuthorizableImpl authorizable) throws RepositoryException {
            if (!node.hasProperty(P_MEMBERS)) {
                log.debug("Group has no members -> cannot remove member {}", authorizable.getID());
                return false;
            }

            Value toRemove = getSession().getValueFactory().createValue((authorizable).getNode(), true);

            PropertyImpl property = node.getProperty(P_MEMBERS);
            List<Value> valList = new ArrayList<Value>(Arrays.asList(property.getValues()));

            if (valList.remove(toRemove)) {
                try {
                    if (valList.isEmpty()) {
                        userManager.removeProtectedItem(property, node);
                    } else {
                        Value[] values = valList.toArray(new Value[valList.size()]);
                        userManager.setProtectedProperty(node, P_MEMBERS, values);
                    }
                    userManager.getMembershipCache().clear();
                    return true;
                } catch (RepositoryException e) {
                    // modification failed -> revert all pending changes.
                    node.refresh(false);
                    throw e;
                }
            } else {
                // nothing changed
                log.debug("Authorizable {} was not member of {}", authorizable.getID(), getID());
                return false;
            }
        }

        public Collection<Authorizable> getMembers(boolean includeIndirect, int type) throws RepositoryException {
            Collection<Authorizable> members = new HashSet<Authorizable>();
            if (node.hasProperty(P_MEMBERS)) {
                for (Value member : node.getProperty(P_MEMBERS).getValues()) {
                    collectMembers(member, members, includeIndirect, type);
                }
            }
            return members;
        }

    }

    private class NodeBasedMembershipProvider implements MembershipProvider {
        private final NodeImpl node;

        public NodeBasedMembershipProvider(NodeImpl node) {
            super();
            this.node = node;
        }

        public boolean addMember(AuthorizableImpl authorizable) throws RepositoryException {
            NodeImpl nMembers = (node.hasNode(N_MEMBERS)
                    ? node.getNode(N_MEMBERS)
                    : userManager.addProtectedNode(node, N_MEMBERS, NT_REP_MEMBERS));

            try {
                PropertySequence properties = getPropertySequence(nMembers);
                String propName = Text.escapeIllegalJcrChars(authorizable.getID());
                if (properties.hasItem(propName)) {
                    log.debug("Authorizable {} is already member of {}", authorizable, this);
                    return false;
                } else {
                    Value newMember = getSession().getValueFactory().createValue(authorizable.getNode(), true);
                    properties.addProperty(propName, newMember);
                }

                if (userManager.isAutoSave()) {
                    node.save();
                }
                userManager.getMembershipCache().clear();
                return true;
            }
            catch (RepositoryException e) {
                log.debug("addMember failed. Reverting changes", e);
                nMembers.refresh(false);
                throw e;
            }
        }

        public boolean removeMember(AuthorizableImpl authorizable) throws RepositoryException {
            if (!node.hasNode(N_MEMBERS)) {
                log.debug("Group has no members -> cannot remove member {}", authorizable.getID());
                return false;
            }

            NodeImpl nMembers = node.getNode(N_MEMBERS);
            try {
                PropertySequence properties = getPropertySequence(nMembers);
                String propName = Text.escapeIllegalJcrChars(authorizable.getID());
                if (properties.hasItem(propName)) {
                    properties.removeProperty(propName);
                    if (!properties.iterator().hasNext()) {
                        userManager.removeProtectedItem(nMembers, node);
                    }
                } else {
                    log.debug("Authorizable {} was not member of {}", authorizable.getID(), getID());
                    return false;
                }

                if (userManager.isAutoSave()) {
                    node.save();
                }
                userManager.getMembershipCache().clear();
                return true;
            }
            catch (RepositoryException e) {
                log.debug("removeMember failed. Reverting changes", e);
                nMembers.refresh(false);
                throw e;
            }
        }

        public Collection<Authorizable> getMembers(boolean includeIndirect, int type)
                throws RepositoryException {

            Collection<Authorizable> members = new HashSet<Authorizable>();
            if (node.hasNode(N_MEMBERS)) {
                for (Property member : getPropertySequence(node.getNode(N_MEMBERS))) {
                    collectMembers(member.getValue(), members, includeIndirect, type);
                }
            }

            return members;
        }

    }
}
