/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright Contributors to the ODPi Egeria project. */
package org.odpi.egeria.connectors.hms.eventmapper;


import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.thrift.TException;
import org.odpi.egeria.connectors.hms.auditlog.HMSOMRSAuditCode;
import org.odpi.egeria.connectors.hms.auditlog.HMSOMRSErrorCode;
import org.odpi.egeria.connectors.hms.helpers.ExceptionHelper;
import org.odpi.egeria.connectors.hms.helpers.SupportedTypes;
import org.odpi.openmetadata.frameworks.connectors.ffdc.ConnectorCheckedException;
import org.odpi.openmetadata.frameworks.connectors.properties.EndpointProperties;
import org.odpi.openmetadata.repositoryservices.ffdc.exception.RepositoryErrorException;

import java.util.*;


/**
 * HMSOMRSRepositoryEventMapper supports the event mapper function for a Hive metastore used as an open metadata repository.
 *
 * This class is an implementation of an OMRS event mapper, it polls for content in Hive metastore and puts
 * that content into an embedded Egeria repository. It then (if configured to send batch events) extracts the entities and relationships
 * from the embedded repository and sends a batch event for
 * 1) for the asset Entities and relationships
 * 2) for each RelationalTable, it's RelationalColumns and associated relationships
 */
public class HMSOMRSRepositoryEventMapper extends OMRSDatabasePollingRepositoryEventMapper
{

    private HiveMetaStoreClient client = null;


    private final String className = this.getClass().getName();

    /**
     * Default constructor
     */
    public HMSOMRSRepositoryEventMapper() {
        super();
    }

    /**
     * Connect to Hive Meta Store using the configuration parameters
     *
     * @throws ConnectorCheckedException could not connect to HMS
     * @throws RepositoryErrorException repository error - endpoint not supplied.
     */
    @Override
    protected void connectTo3rdParty() throws RepositoryErrorException, ConnectorCheckedException {
        String methodName = "connectTo3rdParty";

        Map<String, Object> configurationProperties = connectionProperties.getConfigurationProperties();
        Boolean configuredUseSSL = (Boolean) configurationProperties.get(HMSOMRSRepositoryEventMapperProvider.USE_SSL);
        boolean useSSL = false;
        if (configuredUseSSL != null) {
            useSSL = configuredUseSSL;
        }
        String metadata_store_userId =null;
        String configuredMetadataStoreUserId = (String) configurationProperties.get(HMSOMRSRepositoryEventMapperProvider.METADATA_STORE_USER);
        if (configuredMetadataStoreUserId != null) {
            metadata_store_userId = configuredMetadataStoreUserId;
        }
        String metadata_store_password = null;
        String configuredMetadataStorePassword = (String) configurationProperties.get(HMSOMRSRepositoryEventMapperProvider.METADATA_STORE_PASSWORD);
        if (configuredMetadataStorePassword != null) {
            metadata_store_password = configuredMetadataStorePassword;
        }

        EndpointProperties endpointProperties = connectionProperties.getEndpoint();
        if (endpointProperties == null) {
            ExceptionHelper.raiseRepositoryErrorException(className, HMSOMRSErrorCode.ENDPOINT_NOT_SUPPLIED_IN_CONFIG, methodName, null, "null");
        } else {
            // populate the Hive configuration for the HMS client.
            Configuration conf = new Configuration();
            // we only support one thrift uri at this time
            conf.set("metastore.thrift.uris", endpointProperties.getAddress());
            if (useSSL) {
                conf.set("metastore.use.SSL", "true");
                conf.set("metastore.truststore.path", "file:///" + System.getProperty("java.home") + "/lib/security/cacerts");
                conf.set("metastore.truststore.password", "changeit");
                conf.set("metastore.client.auth.mode", "PLAIN");
                conf.set("metastore.client.plain.username", metadata_store_userId);
                conf.set("metastore.client.plain.password", metadata_store_password);
            }
            // if this is not specified then client side user and group checking occurs on the file system.
            // As the server is remote and may not be on this machine, we remove this check.
            // If this is set / or left to default to true then we get this error:
            // "java.lang.RuntimeException: java.lang.RuntimeException: java.lang.ClassNotFoundException:
            // Class org.apache.hadoop.security.JniBasedUnixGroupsMappingWithFallback not found"

            // TODO consider allowing the user to provide their own config to allow them configuration flexibility
            conf.set("metastore.execute.setugi", "false");

            try {
                client = new HiveMetaStoreClient(conf, null, false);
            } catch (MetaException e) {
                //TODO
                ExceptionHelper.raiseConnectorCheckedException(this.getClass().getName(), HMSOMRSErrorCode.FAILED_TO_START_CONNECTOR, methodName, null);
            }
            metadataCollection = this.repositoryConnector.getMetadataCollection();
            metadataCollectionId = metadataCollection.getMetadataCollectionId(getUserId());
        }
    }

    @Override
    protected List<ConnectorTable> getTablesFrom3rdParty(String catName, String dbName, String baseCanonicalName) {
        String methodName = "refreshRepository";
        List<ConnectorTable> connectorTables = new ArrayList<>();
        List<String> tableNames = new ArrayList<>();

        try {
            tableNames = client.getTables(catName, dbName, "*");
        } catch (TException e) {
            auditLog.logMessage(methodName, HMSOMRSAuditCode.HIVE_GETTABLES_FAILED.getMessageDefinition(e.getMessage()));
        }

        if (tableNames != null && !tableNames.isEmpty()) {
            // create each table and relationship
            for (String tableName : tableNames) {
                Table hmsTable = null;
                try {
                    hmsTable = client.getTable(catName, dbName, tableName);
                } catch (TException e) {
                    auditLog.logMessage(methodName, HMSOMRSAuditCode.HIVE_GETTABLE_FAILED.getMessageDefinition(tableName, e.getMessage()));
                }
                if (hmsTable != null) {
                    ConnectorTable connectorTable = getTableFromHMSTable(baseCanonicalName, hmsTable);
                    Iterator<FieldSchema> colsIterator = hmsTable.getSd().getColsIterator();

                    while (colsIterator.hasNext()) {
                        FieldSchema fieldSchema = colsIterator.next();
                        String columnName = fieldSchema.getName();
                        String dataType = fieldSchema.getType();

                        ConnectorColumn column = new ConnectorColumn();
                        column.setName(columnName);
                        column.setQualifiedName(connectorTable.getQualifiedName() + SupportedTypes.SEPARATOR_CHAR + columnName);
                        column.setType(dataType);
                        connectorTable.addColumn(column);
                    }
                    connectorTables.add(connectorTable);
                }
            }
        }
        return connectorTables;
    }


        private ConnectorTable getTableFromHMSTable(String baseCanonicalName, Table hmsTable) {
            ConnectorTable connectorTable = new ConnectorTable();
            String name = hmsTable.getTableName();
            String tableType = hmsTable.getTableType();
            String tableCanonicalName = baseCanonicalName + SupportedTypes.SEPARATOR_CHAR + "schema" + SupportedTypes.SEPARATOR_CHAR + name;
            String typeName = SupportedTypes.TABLE;
            int createTime = hmsTable.getCreateTime();
            //                            String owner = hmsTable.getOwner();
            //                            if (owner != null) {
            //                               TODO Can we store this on the hmsTable ?
            //                            }

            connectorTable.setName(name);
            connectorTable.setCreateTime(new Date(createTime));
            connectorTable.setQualifiedName(tableCanonicalName);
            connectorTable.setType(tableType);
            connectorTable.setType(typeName);

            Iterator<FieldSchema> colsIterator = hmsTable.getSd().getColsIterator();

            while (colsIterator.hasNext()) {
                ConnectorColumn column = new ConnectorColumn();
                FieldSchema fieldSchema = colsIterator.next();
                String columnName = fieldSchema.getName();
                column.setName(columnName);
                column.setType(fieldSchema.getType());
                column.setQualifiedName(tableCanonicalName + SupportedTypes.SEPARATOR_CHAR + columnName);
            }

            return connectorTable;
        }
}
