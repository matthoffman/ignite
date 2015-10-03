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

package org.apache.ignite.internal.processors.query.h2.opt;

import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import org.apache.ignite.spi.indexing.IndexingQueryFilter;
import org.jetbrains.annotations.Nullable;
import org.jsr166.ConcurrentHashMap8;

import static org.apache.ignite.internal.processors.query.h2.opt.GridH2QueryType.MAP;

/**
 * Thread local SQL query context which is intended to be accessible from everywhere.
 */
public class GridH2QueryContext {
    /** */
    private static final ThreadLocal<GridH2QueryContext> qctx = new ThreadLocal<>();

    /** */
    private static final ConcurrentMap<Key, GridH2QueryContext> qctxs = new ConcurrentHashMap8<>();

    /** */
    private final Key key;

    /** */
    private IndexingQueryFilter filter;

    /**
     * @param locNodeId Local node ID.
     * @param nodeId The node who initiated the query.
     * @param qryId The query ID.
     * @param subQryId Subquery ID in case when query was splitted into multiple subqueries.
     * @param type Query type.
     */
    public GridH2QueryContext(UUID locNodeId, UUID nodeId, long qryId, int subQryId, GridH2QueryType type) {
        key = new Key(locNodeId, nodeId, qryId, subQryId, type);
    }

    /**
     * Sets current thread local context. This method must be called when all the non-volatile properties are
     * already set to ensure visibility for other threads.
     *
     * @param qctx Query context.
     */
     public static void set(GridH2QueryContext qctx) {
         assert GridH2QueryContext.qctx.get() == null;

         if (qctx.key.type == MAP) {
             // Currently only map queries are required to share their context.
             if (qctxs.putIfAbsent(qctx.key, qctx) != null)
                 throw new IllegalStateException("Query context is already set.");
         }

         GridH2QueryContext.qctx.set(qctx);
    }

    /**
     * Drops current thread local context.
     */
    public static void clear() {
        GridH2QueryContext x = qctx.get();

        if (!qctxs.remove(x.key, x))
            throw new IllegalStateException();

        qctx.remove();
    }

    /**
     * Access current thread local query context (if it was set).
     *
     * @return Current thread local query context or {@code null} if the query runs outside of Ignite context.
     */
    @Nullable public static GridH2QueryContext get() {
        return qctx.get();
    }

    /**
     * Access query context from another thread.
     *
     * @param locNodeId Local node ID.
     * @param nodeId The node who initiated the query.
     * @param qryId The query ID.
     * @param subQryId Subquery ID in case when query was splitted into multiple subqueries.
     * @param type Query type.
     * @return Query context.
     */
    @Nullable public static GridH2QueryContext get(
        UUID locNodeId,
        UUID nodeId,
        long qryId,
        int subQryId,
        GridH2QueryType type
    ) {
        return qctxs.get(new Key(locNodeId, nodeId, qryId, subQryId, type));
    }

    /**
     * @return Filter.
     */
    public IndexingQueryFilter filter() {
        return filter;
    }

    /**
     * @param filter Filter.
     * @return {@code this}.
     */
    public GridH2QueryContext filter(IndexingQueryFilter filter) {
        this.filter = filter;

        return this;
    }

    /**
     * Unique key for the query context.
     */
    private static class Key {
        /** */
        final UUID locNodeId;

        /** */
        final UUID nodeId;

        /** */
        final long qryId;

        /** */
        final int subQryId;

        /** */
        final GridH2QueryType type;

        /**
         * @param locNodeId Local node ID.
         * @param nodeId The node who initiated the query.
         * @param qryId The query ID.
         * @param subQryId Subquery ID in case when query was splitted into multiple subqueries.
         * @param type Query type.
         */
        private Key(UUID locNodeId, UUID nodeId, long qryId, int subQryId, GridH2QueryType type) {
            assert locNodeId != null;
            assert nodeId != null;
            assert type != null;

            this.locNodeId = locNodeId;
            this.nodeId = nodeId;
            this.qryId = qryId;
            this.subQryId = subQryId;
            this.type = type;
        }

        /** {@inheritDoc} */
        @Override public boolean equals(Object o) {
            if (this == o)
                return true;

            if (o == null || getClass() != o.getClass())
                return false;

            Key key = (Key)o;

            return qryId == key.qryId && subQryId == key.subQryId && nodeId.equals(key.nodeId) &&
               locNodeId.equals(key.locNodeId) && type == key.type;
        }

        /** {@inheritDoc} */
        @Override public int hashCode() {
            int result = locNodeId.hashCode();

            result = 31 * result + nodeId.hashCode();
            result = 31 * result + (int)(qryId ^ (qryId >>> 32));
            result = 31 * result + subQryId;
            result = 31 * result + type.hashCode();

            return result;
        }
    }
}
