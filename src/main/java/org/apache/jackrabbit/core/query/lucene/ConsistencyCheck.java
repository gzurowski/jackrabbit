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
package org.apache.jackrabbit.core.query.lucene;

import org.apache.jackrabbit.core.state.ItemStateManager;
import org.apache.jackrabbit.core.state.NodeState;
import org.apache.jackrabbit.core.state.ItemStateException;
import org.apache.jackrabbit.core.NodeId;
import org.apache.jackrabbit.uuid.UUID;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.document.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;

/**
 * Implements a consistency check on the search index. Currently the following
 * checks are implemented:
 * <ul>
 * <li>Does not node exist in the ItemStateManager? If it does not exist
 * anymore the node is deleted from the index.</li>
 * <li>Is the parent of a node also present in the index? If it is not present it
 * will be indexed.</li>
 * <li>Is a node indexed multiple times? If that is the case, all occurrences
 * in the index for such a node are removed, and the node is re-indexed.</li>
 * </ul>
 */
class ConsistencyCheck {

    /**
     * Logger instance for this class
     */
    private static final Logger log = LoggerFactory.getLogger(ConsistencyCheck.class);

    /**
     * The ItemStateManager of the workspace.
     */
    private final ItemStateManager stateMgr;

    /**
     * The index to check.
     */
    private final MultiIndex index;

    /**
     * All the document UUIDs within the index.
     */
    private Set documentUUIDs;

    /**
     * List of all errors.
     */
    private final List errors = new ArrayList();

    /**
     * Private constructor.
     */
    private ConsistencyCheck(MultiIndex index, ItemStateManager mgr) {
        this.index = index;
        this.stateMgr = mgr;
    }

    /**
     * Runs the consistency check on <code>index</code>.
     *
     * @param index the index to check.
     * @param mgr   the ItemStateManager from where to load content.
     * @return the consistency check with the results.
     * @throws IOException if an error occurs while checking.
     */
    static ConsistencyCheck run(MultiIndex index, ItemStateManager mgr) throws IOException {
        ConsistencyCheck check = new ConsistencyCheck(index, mgr);
        check.run();
        return check;
    }

    /**
     * Repairs detected errors during the consistency check.
     * @param ignoreFailure if <code>true</code> repair failures are ignored,
     *   the repair continues without throwing an exception. If
     *   <code>false</code> the repair procedure is aborted on the first
     *   repair failure.
     * @throws IOException if a repair failure occurs.
     */
    void repair(boolean ignoreFailure) throws IOException {
        if (errors.size() == 0) {
            log.info("No errors found.");
            return;
        }
        int notRepairable = 0;
        for (Iterator it = errors.iterator(); it.hasNext();) {
            ConsistencyCheckError error = (ConsistencyCheckError) it.next();
            try {
                if (error.repairable()) {
                    error.repair();
                } else {
                    log.warn("Not repairable: " + error);
                    notRepairable++;
                }
            } catch (Exception e) {
                if (ignoreFailure) {
                    log.warn("Exception while reparing: " + e);
                } else {
                    if (!(e instanceof IOException)) {
                        e = new IOException(e.getMessage());
                    }
                    throw (IOException) e;
                }
            }
        }
        log.info("Repaired " + (errors.size() - notRepairable) + " errors.");
        if (notRepairable > 0) {
            log.warn("" + notRepairable + " error(s) not repairable.");
        }
    }

    /**
     * Returns the errors detected by the consistency check.
     * @return the errors detected by the consistency check.
     */
    List getErrors() {
        return new ArrayList(errors);
    }

    /**
     * Runs the consistency check.
     * @throws IOException if an error occurs while running the check.
     */
    private void run() throws IOException {
        // UUIDs of multiple nodes in the index
        Set multipleEntries = new HashSet();
        // collect all documents UUIDs
        documentUUIDs = new HashSet();
        IndexReader reader = index.getIndexReader();
        try {
            for (int i = 0; i < reader.maxDoc(); i++) {
                if (i > 10 && i % (reader.maxDoc() / 5) == 0) {
                    long progress = Math.round((100.0 * (float) i) / ((float) reader.maxDoc() * 2f));
                    log.info("progress: " + progress + "%");
                }
                if (reader.isDeleted(i)) {
                    continue;
                }
                Document d = reader.document(i);
                UUID uuid = UUID.fromString(d.get(FieldNames.UUID));
                if (stateMgr.hasItemState(new NodeId(uuid))) {
                    if (!documentUUIDs.add(uuid)) {
                        multipleEntries.add(uuid);
                    }
                } else {
                    errors.add(new NodeDeleted(uuid));
                }
            }
        } finally {
            reader.close();
        }

        // create multiple entries errors
        for (Iterator it = multipleEntries.iterator(); it.hasNext();) {
            errors.add(new MultipleEntries((UUID) it.next()));
        }

        reader = index.getIndexReader();
        try {
            // run through documents again and check parent
            for (int i = 0; i < reader.maxDoc(); i++) {
                if (i > 10 && i % (reader.maxDoc() / 5) == 0) {
                    long progress = Math.round((100.0 * (float) i) / ((float) reader.maxDoc() * 2f));
                    log.info("progress: " + (progress + 50) + "%");
                }
                if (reader.isDeleted(i)) {
                    continue;
                }
                Document d = reader.document(i);
                UUID uuid = UUID.fromString(d.get(FieldNames.UUID));
                String parentUUIDString = d.get(FieldNames.PARENT);
                UUID parentUUID = null;
                if (parentUUIDString.length() > 0) {
                    parentUUID = UUID.fromString(parentUUIDString);
                }
                if (parentUUID == null || documentUUIDs.contains(parentUUID)) {
                    continue;
                }
                // parent is missing
                NodeId parentId = new NodeId(parentUUID);
                if (stateMgr.hasItemState(parentId)) {
                    errors.add(new MissingAncestor(uuid, parentUUID));
                } else {
                    errors.add(new UnknownParent(uuid, parentUUID));
                }
            }
        } finally {
            reader.close();
        }
    }

    /**
     * Returns the path for <code>node</code>. If an error occurs this method
     * returns the uuid of the node.
     *
     * @param node the node to retrieve the path from
     * @return the path of the node or its uuid.
     */
    private String getPath(NodeState node) {
        // remember as fallback
        String uuid = node.getNodeId().toString();
        StringBuffer path = new StringBuffer();
        List elements = new ArrayList();
        try {
            while (node.getParentId() != null) {
                NodeId parentId = node.getParentId();
                NodeState parent = (NodeState) stateMgr.getItemState(parentId);
                NodeState.ChildNodeEntry entry = parent.getChildNodeEntry(node.getNodeId());
                elements.add(entry);
                node = parent;
            }
            for (int i = elements.size() - 1; i > -1; i--) {
                NodeState.ChildNodeEntry entry = (NodeState.ChildNodeEntry) elements.get(i);
                path.append('/').append(entry.getName().getLocalName());
                if (entry.getIndex() > 1) {
                    path.append('[').append(entry.getIndex()).append(']');
                }
            }
            if (path.length() == 0) {
                path.append('/');
            }
            return path.toString();
        } catch (ItemStateException e) {
            return uuid;
        }
    }

    //-------------------< ConsistencyCheckError classes >----------------------

    /**
     * One or more ancestors of an indexed node are not available in the index.
     */
    private class MissingAncestor extends ConsistencyCheckError {

        private final UUID parentUUID;

        private MissingAncestor(UUID uuid, UUID parentUUID) {
            super("Parent of " + uuid + " missing in index. Parent: " + parentUUID, uuid);
            this.parentUUID = parentUUID;
        }

        /**
         * Returns <code>true</code>.
         * @return <code>true</code>.
         */
        public boolean repairable() {
            return true;
        }

        /**
         * Repairs the missing node by indexing the missing ancestors.
         * @throws IOException if an error occurs while repairing.
         */
        public void repair() throws IOException {
            NodeId parentId = new NodeId(parentUUID);
            while (parentId != null && !documentUUIDs.contains(parentId.getUUID())) {
                try {
                    NodeState n = (NodeState) stateMgr.getItemState(parentId);
                    log.info("Reparing missing node " + getPath(n));
                    Document d = index.createNodeIndexer(n).createDoc();
                    index.addDocument(d);
                    documentUUIDs.add(n.getNodeId().getUUID());
                    parentId = n.getParentId();
                } catch (ItemStateException e) {
                    throw new IOException(e.toString());
                } catch (RepositoryException e) {
                    throw new IOException(e.toString());
                }
            }
        }
    }

    /**
     * The parent of a node is not available through the ItemStateManager.
     */
    private class UnknownParent extends ConsistencyCheckError {

        private UnknownParent(UUID uuid, UUID parentUUID) {
            super("Node " + uuid + " has unknown parent: " + parentUUID, uuid);
        }

        /**
         * Not reparable (yet).
         * @return <code>false</code>.
         */
        public boolean repairable() {
            return false;
        }

        /**
         * No operation.
         */
        public void repair() throws IOException {
            log.warn("Unknown parent for " + uuid + " cannot be repaired");
        }
    }

    /**
     * A node is present multiple times in the index.
     */
    private class MultipleEntries extends ConsistencyCheckError {

        MultipleEntries(UUID uuid) {
            super("Multiple entries found for node " + uuid, uuid);
        }

        /**
         * Returns <code>true</code>.
         * @return <code>true</code>.
         */
        public boolean repairable() {
            return true;
        }

        /**
         * Removes the nodes with the identical uuids from the index and
         * re-index the node.
         * @throws IOException if an error occurs while repairing.
         */
        public void repair() throws IOException {
            // first remove all occurrences
            index.removeAllDocuments(uuid);
            // then re-index the node
            try {
                NodeState node = (NodeState) stateMgr.getItemState(new NodeId(uuid));
                log.info("Re-indexing duplicate node occurrences in index: " + getPath(node));
                Document d = index.createNodeIndexer(node).createDoc();
                index.addDocument(d);
                documentUUIDs.add(node.getNodeId().getUUID());
            } catch (ItemStateException e) {
                throw new IOException(e.toString());
            } catch (RepositoryException e) {
                throw new IOException(e.toString());
            }
        }
    }

    /**
     * Indicates that a node has been deleted but is still in the index.
     */
    private class NodeDeleted extends ConsistencyCheckError {

        NodeDeleted(UUID uuid) {
            super("Node " + uuid + " does not longer exist.", uuid);
        }

        /**
         * Returns <code>true</code>.
         * @return <code>true</code>.
         */
        public boolean repairable() {
            return true;
        }

        /**
         * Deletes the nodes from the index.
         * @throws IOException if an error occurs while repairing.
         */
        public void repair() throws IOException {
            log.info("Removing deleted node from index: " + uuid);
            index.removeDocument(uuid);
        }
    }
}
