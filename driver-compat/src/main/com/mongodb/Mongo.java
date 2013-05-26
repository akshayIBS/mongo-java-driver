/*
 * Copyright (c) 2008 - 2013 10gen, Inc. <http://10gen.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb;

import org.mongodb.Codec;
import org.mongodb.Document;
import org.mongodb.annotations.ThreadSafe;
import org.mongodb.codecs.DocumentCodec;
import org.mongodb.codecs.PrimitiveCodecs;
import org.mongodb.command.ListDatabases;
import org.mongodb.connection.BufferPool;
import org.mongodb.connection.Cluster;
import org.mongodb.connection.ClusterDescription;
import org.mongodb.connection.Clusters;
import org.mongodb.connection.PowerOfTwoByteBufferPool;
import org.mongodb.connection.ServerDescription;

import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.mongodb.connection.ClusterConnectionMode.Discovering;
import static org.mongodb.connection.ClusterType.ReplicaSet;

@ThreadSafe
public class Mongo {
    static final String ADMIN_DATABASE_NAME = "admin";
    private static final String VERSION = "3.0.0-SNAPSHOT";

    private final ConcurrentMap<String, DB> dbCache = new ConcurrentHashMap<String, DB>();

    private volatile WriteConcern writeConcern;
    private volatile ReadPreference readPreference;

    private final Bytes.OptionHolder optionHolder;

    private final Codec<Document> documentCodec;
    private final Cluster cluster;
    private final BufferPool<ByteBuffer> bufferPool = new PowerOfTwoByteBufferPool();

    Mongo(final List<ServerAddress> seedList, final MongoClientOptions mongoOptions) {
        this(Clusters.create(createNewSeedList(seedList), mongoOptions.toNew()), mongoOptions);
    }

    Mongo(final MongoClientURI mongoURI) throws UnknownHostException {
        this(createCluster(mongoURI.toNew()), mongoURI.getOptions());
    }

    Mongo(final ServerAddress serverAddress, final MongoClientOptions mongoOptions) {
        this(Clusters.create(serverAddress.toNew(), mongoOptions.toNew()), mongoOptions);
    }

    Mongo(final Cluster cluster, final MongoClientOptions options) {
        this.cluster = cluster;
        this.documentCodec = new DocumentCodec(PrimitiveCodecs.createDefault());
        this.readPreference = options.getReadPreference() != null ?
                options.getReadPreference() : ReadPreference.primary();
        this.writeConcern = options.getWriteConcern() != null ?
                options.getWriteConcern() : WriteConcern.UNACKNOWLEDGED;
        this.optionHolder = new Bytes.OptionHolder(null);
    }

    /**
     * Sets the write concern for this database. Will be used as default for writes to any collection in any database.
     * See the documentation for {@link WriteConcern} for more information.
     *
     * @param writeConcern write concern to use
     */
    public void setWriteConcern(final WriteConcern writeConcern) {
        this.writeConcern = writeConcern;
    }


    /**
     * Gets the default write concern
     *
     * @return the default write concern
     */
    public WriteConcern getWriteConcern() {
        return writeConcern;
    }

    /**
     * Sets the read preference for this database. Will be used as default for reads from any collection in any
     * database. See the documentation for {@link ReadPreference} for more information.
     *
     * @param readPreference Read Preference to use
     */
    public void setReadPreference(final ReadPreference readPreference) {
        this.readPreference = readPreference;
    }

    /**
     * Gets the default read preference
     *
     * @return the default read preference
     */
    public ReadPreference getReadPreference() {
        return readPreference;
    }

    /**
     * Gets the current driver version.
     *
     * @return the full version string, e.g. "3.0.0"
     */
    public static String getVersion() {
        return VERSION;
    }

    /**
     * Gets a list of all server addresses used when this Mongo was created
     *
     * @return list of server addresses
     * @throws MongoException
     */
    public List<ServerAddress> getAllAddress() {
        //TODO It should return the address list without auto-discovered nodes. Not sure if it's required. Maybe users confused with name.
        return getServerAddressList();
    }

    /**
     * Gets the list of server addresses currently seen by this client. This includes addresses auto-discovered from a
     * replica set.
     *
     * @return list of server addresses
     * @throws MongoException
     */
    public List<ServerAddress> getServerAddressList() {
        List<ServerAddress> serverAddresses = new ArrayList<ServerAddress>();
        for (ServerDescription cur : cluster.getDescription().getAll()) {
            serverAddresses.add(new ServerAddress(cur.getAddress()));
        }
        return serverAddresses;
    }

    /**
     * Gets the address of the current master
     *
     * @return the address
     */
    public ServerAddress getAddress() {
        final ClusterDescription description = cluster.getDescription();
        if (description.getPrimaries().isEmpty()) {
            return null;
        }
        return new ServerAddress(description.getPrimaries().get(0).getAddress());
    }

    /**
     * Get the status of the replica set cluster.
     *
     * @return replica set status information
     */
    public ReplicaSetStatus getReplicaSetStatus() {
        return cluster.getDescription().getType() == ReplicaSet && cluster.getDescription().getMode() == Discovering
                ? new ReplicaSetStatus(cluster) : null; // this is intended behavior in 2.x
    }


    /**
     * Gets a list of the names of all databases on the connected server.
     *
     * @return list of database names
     * @throws MongoException
     */
    public List<String> getDatabaseNames() {
        final org.mongodb.operation.CommandResult listDatabasesResult;
        try {
            listDatabasesResult = getDB(ADMIN_DATABASE_NAME).executeCommand(new ListDatabases());
        } catch (org.mongodb.MongoException e) {
            throw new MongoException(e);
        }

        @SuppressWarnings("unchecked")
        final List<Document> databases = (List<Document>) listDatabasesResult.getResponse().get("databases");

        final List<String> databaseNames = new ArrayList<String>();
        for (final Document d : databases) {
            databaseNames.add(d.get("name", String.class));
        }
        return Collections.unmodifiableList(databaseNames);
    }

    /**
     * Gets a database object
     *
     * @param dbName the name of the database to retrieve
     * @return a DB representing the specified database
     */
    public DB getDB(final String dbName) {
        DB db = dbCache.get(dbName);
        if (db != null) {
            return db;
        }

        db = new DB(this, dbName, documentCodec);
        final DB temp = dbCache.putIfAbsent(dbName, db);
        if (temp != null) {
            return temp;
        }
        return db;
    }

    /**
     * Returns the list of databases used by the driver since this Mongo instance was created.
     * This may include DBs that exist in the client but not yet on the server.
     *
     * @return a collection of database objects
     */
    public Collection<DB> getUsedDatabases() {
        return dbCache.values();
    }


    /**
     * Drops the database if it exists.
     *
     * @param dbName name of database to drop
     * @throws MongoException
     */
    public void dropDatabase(final String dbName) {
        getDB(dbName).dropDatabase();
    }

    /**
     * Closes all resources associated with this instance, in particular any open network connections. Once called, this
     * instance and any databases obtained from it can no longer be used.
     */
    public void close() {
        cluster.close();
    }

    /**
     * Makes it possible to run read queries on secondary nodes
     *
     * @see ReadPreference#secondaryPreferred()
     * @deprecated Replaced with {@code ReadPreference.secondaryPreferred()}
     */
    @Deprecated
    public void slaveOk() {
        addOption(Bytes.QUERYOPTION_SLAVEOK);
    }

    /**
     * Set the default query options.
     *
     * @param options value to be set
     */
    public void setOptions(final int options) {
        optionHolder.set(options);
    }

    /**
     * Reset the default query options.
     */
    public void resetOptions() {
        optionHolder.reset();
    }

    /**
     * Add the default query option.
     *
     * @param option value to be added to current options
     */
    public void addOption(final int option) {
        optionHolder.add(option);
    }

    public int getOptions() {
        return optionHolder.get();
    }

    private static List<org.mongodb.connection.ServerAddress> createNewSeedList(final List<ServerAddress> seedList) {
        List<org.mongodb.connection.ServerAddress> retVal = new ArrayList<org.mongodb.connection.ServerAddress>(seedList.size());
        for (ServerAddress cur : seedList) {
            retVal.add(cur.toNew());
        }
        return retVal;
    }

    private static Cluster createCluster(final org.mongodb.MongoClientURI mongoURI) throws UnknownHostException {
        if (mongoURI.getHosts().size() == 1) {
            return Clusters.create(new org.mongodb.connection.ServerAddress(mongoURI.getHosts().get(0)),
                    mongoURI.getCredentials(), mongoURI.getOptions());
        }
        else {
            List<org.mongodb.connection.ServerAddress> seedList = new ArrayList<org.mongodb.connection.ServerAddress>();
            for (String cur : mongoURI.getHosts()) {
                seedList.add(new org.mongodb.connection.ServerAddress(cur));
            }
            return Clusters.create(seedList, mongoURI.getCredentials(), mongoURI.getOptions());
        }
    }

    Cluster getCluster() {
        return cluster;
    }

    Bytes.OptionHolder getOptionHolder() {
        return optionHolder;
    }

    BufferPool<ByteBuffer> getBufferPool() {
        return bufferPool;
    }
}
