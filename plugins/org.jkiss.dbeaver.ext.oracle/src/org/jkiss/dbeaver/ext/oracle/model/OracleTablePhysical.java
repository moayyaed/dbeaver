/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2023 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ext.oracle.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBConstants;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCStatement;
import org.jkiss.dbeaver.model.impl.AbstractExecutionSource;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCUtils;
import org.jkiss.dbeaver.model.impl.jdbc.cache.JDBCObjectLookupCache;
import org.jkiss.dbeaver.model.meta.*;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSDataContainer;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectLazy;
import org.jkiss.dbeaver.model.struct.rdb.DBSPartitionContainer;
import org.jkiss.utils.CommonUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Oracle physical table
 */
public abstract class OracleTablePhysical extends OracleTableBase implements DBSObjectLazy<OracleDataSource>, DBSPartitionContainer
{
    private static final Log log = Log.getLog(OracleTablePhysical.class);

    //private boolean valid;
    private long rowCount;
    private Long realRowCount;
    private Object tablespace;
    private boolean partitioned;
    private PartitionInfo partitionInfo;
    private PartitionCache partitionCache;

    protected OracleTablePhysical(OracleSchema schema, String name)
    {
        super(schema, name, false);
    }

    protected OracleTablePhysical(
        OracleSchema schema,
        ResultSet dbResult)
    {
        super(schema, dbResult);
        readSpecialProperties(dbResult);

        this.partitioned = JDBCUtils.safeGetBoolean(dbResult, "PARTITIONED", OracleConstants.RESULT_YES_VALUE);
        this.partitionCache = partitioned ? new PartitionCache() : null;
    }

    protected OracleTablePhysical(@NotNull OracleSchema schema, @NotNull ResultSet dbResult, @NotNull String name) {
        // Table partition
        super(schema, name);
        readSpecialProperties(dbResult);
        this.partitioned = false;
    }

    private void readSpecialProperties(@NotNull ResultSet dbResult) {
        this.rowCount = JDBCUtils.safeGetLong(dbResult, "NUM_ROWS");
        this.tablespace = JDBCUtils.safeGetString(dbResult, "TABLESPACE_NAME");
    }

    @Property(category = DBConstants.CAT_STATISTICS, viewable = true, order = 20)
    public long getRowCount()
    {
        return rowCount;
    }

    @Property(category = DBConstants.CAT_STATISTICS, viewable = false, expensive = true, order = 21)
    public synchronized Long getRealRowCount(DBRProgressMonitor monitor)
    {
        if (realRowCount != null) {
            return realRowCount;
        }
        if (!isPersisted()) {
            // Do not count rows for views
            return null;
        }

        // Query row count
        try (DBCSession session = DBUtils.openMetaSession(monitor, this, "Read row count")) {
            realRowCount = countData(new AbstractExecutionSource(this, session.getExecutionContext(), this), session, null, DBSDataContainer.FLAG_NONE);
        } catch (DBException e) {
            log.debug("Can't fetch row count", e);
        }
        if (realRowCount == null) {
            realRowCount = -1L;
        }

        return realRowCount;
    }

    @Override
    public Object getLazyReference(Object propertyId)
    {
        return tablespace;
    }

    @Property(viewable = true, order = 22, editable = true, updatable = true, listProvider = TablespaceListProvider.class)
    @LazyProperty(cacheValidator = OracleTablespace.TablespaceReferenceValidator.class)
    public Object getTablespace(DBRProgressMonitor monitor) throws DBException
    {
        return OracleTablespace.resolveTablespaceReference(monitor, this, null);
    }

    public Object getTablespace() {
        return tablespace;
    }

    public void setTablespace(OracleTablespace tablespace) {
        this.tablespace = tablespace;
    }

    @Override
    @Association
    public Collection<OracleTableIndex> getIndexes(DBRProgressMonitor monitor)
        throws DBException
    {
        // Read indexes using cache
        return this.getContainer().indexCache.getObjects(monitor, getContainer(), this);
    }

    public OracleTableIndex getIndex(DBRProgressMonitor monitor, String name)
        throws DBException
    {
        return this.getContainer().indexCache.getObject(monitor, getContainer(), this, name);
    }

    public PartitionCache getPartitionCache() {
        return partitionCache;
    }

    @PropertyGroup
    @LazyProperty(cacheValidator = PartitionInfoValidator.class)
    public PartitionInfo getPartitionInfo(DBRProgressMonitor monitor) throws DBException
    {
        if (partitionInfo == null && partitioned) {
            try (final JDBCSession session = DBUtils.openMetaSession(monitor, this, "Load partitioning info")) {
                try (JDBCPreparedStatement dbStat = session.prepareStatement("SELECT * FROM ALL_PART_TABLES WHERE OWNER=? AND TABLE_NAME=?")) {
                    dbStat.setString(1, getContainer().getName());
                    dbStat.setString(2, getName());
                    try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                        if (dbResult.next()) {
                            partitionInfo = new PartitionInfo(monitor, this.getDataSource(), dbResult);
                        }
                    }
                }
            } catch (SQLException e) {
                throw new DBException(e, getDataSource());
            }
        }
        return partitionInfo;
    }

    @Association
    @Property(viewable = true, order = 13)
    public boolean isPartitioned() {
        return partitioned;
    }

    @Association
    public Collection<OracleTablePartition> getPartitions(DBRProgressMonitor monitor) throws DBException {
        if (partitionCache == null) {
            return Collections.emptyList();
        }
        return partitionCache.getAllObjects(monitor, this);
    }

    @Override
    public DBSObject refreshObject(@NotNull DBRProgressMonitor monitor) throws DBException
    {
        this.getContainer().indexCache.clearObjectCache(this);
        if (partitionCache != null) {
            partitionCache.clearCache();
            partitionInfo = null;
        }
        return super.refreshObject(monitor);
    }

    @Override
    public void refreshObjectState(@NotNull DBRProgressMonitor monitor) throws DBCException
    {
        this.valid = OracleUtils.getObjectStatus(monitor, this, OracleObjectType.TABLE);
    }

    private static class PartitionCache extends JDBCObjectLookupCache<OracleTablePhysical, OracleTablePartition> {

        @NotNull
        @Override
        public JDBCStatement prepareLookupStatement(
            @NotNull JDBCSession session,
            @NotNull OracleTablePhysical table,
            @Nullable OracleTablePartition partition,
            @Nullable String partitionName
        ) throws SQLException {
            final JDBCPreparedStatement dbStat = session.prepareStatement(
                "SELECT * FROM " + OracleUtils.getSysSchemaPrefix(table.getDataSource()) + "ALL_TAB_PARTITIONS " +
                    "\nWHERE TABLE_OWNER=? AND TABLE_NAME=? " +
                    (partition != null || CommonUtils.isNotEmpty(partitionName) ? " AND PARTITION_NAME=?" : "") +
                    "\nORDER BY PARTITION_POSITION");
            dbStat.setString(1, table.getContainer().getName());
            dbStat.setString(2, table.getName());
            if (partition != null || CommonUtils.isNotEmpty(partitionName)) {
                dbStat.setString(3, partition != null ? partition.getName() : partitionName);
            }
            return dbStat;
        }

        @Override
        protected OracleTablePartition fetchObject(
            @NotNull JDBCSession session,
            @NotNull OracleTablePhysical table,
            @NotNull JDBCResultSet resultSet
        ) throws SQLException, DBException {
            String partitionName = JDBCUtils.safeGetString(resultSet, "PARTITION_NAME");
            if (CommonUtils.isEmpty(partitionName)) {
                return null;
            }
            return new OracleTablePartition(table, partitionName, resultSet, null);
        }
    }

    public static class PartitionInfo extends OracleTablePartition.PartitionInfoBase {

        public PartitionInfo(DBRProgressMonitor monitor, OracleDataSource dataSource, ResultSet dbResult) {
            super(monitor, dataSource, dbResult);
        }
    }

    public static class PartitionInfoValidator implements IPropertyCacheValidator<OracleTablePhysical> {
        @Override
        public boolean isPropertyCached(OracleTablePhysical object, Object propertyId)
        {
            return object.partitioned && object.partitionInfo != null;
        }
    }

    public static class TablespaceListProvider implements IPropertyValueListProvider<OracleTablePhysical> {
        @Override
        public boolean allowCustomValue()
        {
            return false;
        }
        @Override
        public Object[] getPossibleValues(OracleTablePhysical object)
        {
            final List<OracleTablespace> tablespaces = new ArrayList<>();
            try {
                tablespaces.addAll(object.getDataSource().getTablespaces(new VoidProgressMonitor()));
            } catch (DBException e) {
                log.error(e);
            }
            tablespaces.sort(DBUtils.<OracleTablespace>nameComparator());
            return tablespaces.toArray(new OracleTablespace[tablespaces.size()]);
        }
    }
}
