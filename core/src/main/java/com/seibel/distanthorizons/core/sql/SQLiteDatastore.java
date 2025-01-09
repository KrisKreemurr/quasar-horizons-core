package com.seibel.distanthorizons.core.sql;

import com.seibel.distanthorizons.api.interfaces.override.datastore.IDhApiDatastore;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiWorldUnloadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IOverrideInjector;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.sql.*;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class SQLiteDatastore extends DhApiWorldUnloadEvent implements IDhApiDatastore {
    private static final Logger LOGGER = DhLoggerBuilder.getLogger();

    // This ensures that only a single instance of SQLiteDatastore exists
    // while code is migrated.
    @Deprecated
    public static final SQLiteDatastore SINGLETON = new SQLiteDatastore();

    private final ReadWriteLock mutex = new ReentrantReadWriteLock();
    private final Map<Integer, Connection> connectionByLevelId = new HashMap<>();
    private final Map<Integer, String> databaseUrlByLevelId = new HashMap<>();

    private SQLiteDatastore() {
        try {
            // needed by Forge to load the Java database connection
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

        DhApiEventRegister.on(DhApiWorldUnloadEvent.class, this);
    }

    @Override
    public void onWorldUnload(DhApiEventParam<EventParam> input) {
        mutex.writeLock().lock();
        try {
            connectionByLevelId.forEach((levelId, connection) -> {
                String databaseUrl = "unknown database url";
                try {
                    LOGGER.info("Closing connections to {}", connection.getMetaData().getURL());
                    connection.close();
                } catch (SQLException err) {
                    LOGGER.error("Exception while closing database connection [{}]", databaseUrl, err);
                }
            });

            databaseUrlByLevelId.clear();
            connectionByLevelId.clear();
        } finally {
            mutex.writeLock().unlock();
        }
    }

    @Override
    public int getPriority() {
        return IOverrideInjector.CORE_PRIORITY;
    }

    //=============================//
    // concurrency support wrapper //
    //=============================//

    @FunctionalInterface
    private interface Operation<T> {
        T run(Connection connection) throws SQLException;

    }

    /***
     * withConnection provides a wrapper for concurrently fetching a connection for use.
     */
    private <T> T withConnection(int levelId, Operation<T> operation) {
        Connection connection = null;

        // Try take existing connection
        mutex.readLock().lock();
        try {
            connection = connectionByLevelId.get(levelId);
            if (connection != null)
                return operation.run(connection);
        } catch (SQLException err) {
            LOGGER.error(err);
            mutex.readLock().unlock();
            mutex.writeLock().lock();
            try {
                connectionByLevelId.remove(levelId);
                connection.close();
            } catch (SQLException ignored) {
                // we're closing this connection because trying to use it threw an error
                // if closing also threw then the original error is almost certainly what we actually want to see and address.
            } finally {
                mutex.writeLock().unlock();
                mutex.readLock().lock();
            }
        } finally {
            mutex.readLock().unlock();
        }

        // Make a new connection, making sure to check one hasn't been made in the meantime.
        mutex.writeLock().lock();
        try {
            return operation.run(connectionByLevelId.computeIfAbsent(levelId, id -> {
                try {
                    String databaseUrl = databaseUrlByLevelId.get(levelId);
                    if (databaseUrl == null) {
                        throw new RuntimeException("no such level " + levelId);
                    }

                    return DriverManager.getConnection(databaseUrl);
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }));
        } catch (SQLException err) {
            LOGGER.error(err);
            throw new RuntimeException(err);
        } finally {
            mutex.writeLock().unlock();
        }
    }

    // This allows legacy code to access a Connection instance
    // while bring migrated to IDhApiDatastore
    @Deprecated
    public Connection getConnection(int levelId) throws SQLException {
        return withConnection(levelId, connection -> connection);
    }

    //=========================//
    // IDhApiDatastore methods //
    //=========================//

    /***
     * ensureLevel implements {@link IDhApiDatastore#ensureLevel(String)}
     *
     * uniqueLevelName is actually the location of the SQLite database,
     * this is not clarified in the API because it's SQLite specific and
     * users shouldn't be relying on it.
     */
    @Override
    public int ensureLevel(String uniqueLevelName) {
        File databaseFile = new File(uniqueLevelName);
        int levelId = databaseFile.getAbsolutePath().hashCode();

        mutex.writeLock().lock();
        try {
            databaseUrlByLevelId.putIfAbsent(levelId, "jdbc:sqlite://" + databaseFile.getAbsolutePath());
        } finally {
            mutex.writeLock().unlock();
        }

        // check that the database file exists
        if (!databaseFile.exists()) {
            // check that the parent folder exists
            File parentFolder = databaseFile.getParentFile();
            if (parentFolder != null && !parentFolder.exists()) {
                if (!parentFolder.mkdirs()) {
                    throw new RuntimeException("Unable to create the necessary parent folders for the database file at location [" + databaseFile.getPath() + "].");
                }
            }

            if (!databaseFile.exists()) {
                try {
                    boolean fileCreated = databaseFile.createNewFile();
                } catch (IOException e) {
                    throw new RuntimeException("Unable to create database file at location [" + databaseFile.getPath() + "] due to error: [" + e.getMessage() + "]", e);
                }
            }
        }

        if (!databaseFile.canRead()) {
            throw new RuntimeException("Unable to read database file at location [" + databaseFile.getPath() + "], please make sure the folder and file has the correct permissions.");
        }
        if (!databaseFile.canWrite()) {
            throw new RuntimeException("Unable to write database file at location [" + databaseFile.getPath() + "], please make sure the folder and file aren't set to read-only.");
        }

        withConnection(levelId, DatabaseUpdater::runAutoUpdateScripts);
        return levelId;
    }


    @Override
    public int getChunkHash(int levelId, int posX, int posZ) {
        return withConnection(levelId, connection -> {
            try (PreparedStatement preparedStatement = connection.prepareStatement(
                    "select ChunkHash from ChunkHash where ChunkPosX = ? and ChunkPosZ = ?"
            )) {
                preparedStatement.setInt(1, posX);
                preparedStatement.setInt(2, posZ);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    if (!resultSet.next())
                        return 0;
                    return resultSet.getInt("ChunkHash");
                }
            }
        });
    }

    @Override
    public void setChunkHash(int levelId, int posX, int posZ, int hash) {
        withConnection(levelId, connection -> {
            try (PreparedStatement preparedStatement = connection.prepareStatement(
                    "insert into ChunkHash (ChunkPosX, ChunkPosZ, ChunkHash, LastModifiedUnixDateTime, CreatedUnixDateTime) " +
                    "values (?, ?, ?, ?, ?) " +
                    "on conflict(ChunkPosX, ChunkPosZ) do update set " +
                    "ChunkPosX=excluded.ChunkPosX, " +
                    "ChunkPosZ=excluded.ChunkPosZ, " +
                    "ChunkHash=excluded.ChunkHash, " +
                    "LastModifiedUnixDateTime=excluded.LastModifiedUnixDateTime"
            )) {
                preparedStatement.setInt(1, posX);
                preparedStatement.setInt(2, posZ);
                preparedStatement.setInt(3, hash);
                preparedStatement.setTimestamp(4, Timestamp.from(Instant.now()));
                preparedStatement.setTimestamp(5, Timestamp.from(Instant.now()));
                return preparedStatement.executeUpdate();
            }
        });
    }

}
