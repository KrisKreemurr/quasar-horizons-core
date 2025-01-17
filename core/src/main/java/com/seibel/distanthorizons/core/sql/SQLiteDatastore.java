package com.seibel.distanthorizons.core.sql;

import com.seibel.distanthorizons.api.enums.config.EDhApiDataCompressionMode;
import com.seibel.distanthorizons.api.interfaces.override.datastore.IDhApiDatastore;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiWorldUnloadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataInputStream;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataOutputStream;
import com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IOverrideInjector;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class SQLiteDatastore extends DhApiWorldUnloadEvent implements IDhApiDatastore {
    private static final Logger LOGGER = DhLoggerBuilder.getLogger();

    private final EDhApiDataCompressionMode compressionMode;
    private final ReadWriteLock mutex = new ReentrantReadWriteLock();
    private final Map<Integer, Connection> connectionByLevelId = new HashMap<>();
    private final Map<Integer, String> databaseUrlByLevelId = new HashMap<>();

    public SQLiteDatastore(EDhApiDataCompressionMode compressionMode) {
        this.compressionMode = compressionMode;
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

    // This allows legacy code to access a Connection instance
    // while bring migrated to IDhApiDatastore
    @Deprecated
    public Connection getConnection(int levelId) {
        return withConnection(levelId, connection -> connection);
    }

    //=====================//
    // concurrency support //
    //=====================//

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
                // we're closing this connection because trying to use it threw an SQLException
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
                    return DriverManager.getConnection(databaseUrlByLevelId.get(levelId));
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }));
        } catch (SQLException err) {
            throw new RuntimeException(err);
        } finally {
            mutex.writeLock().unlock();
        }
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
    public void setChunkHash(int levelId, int posX, int posZ, int hash) {
        withConnection(levelId, connection -> {
            try (PreparedStatement preparedStatement = connection.prepareStatement(
                    "insert into ChunkHash (ChunkPosX, ChunkPosZ, ChunkHash, LastModifiedUnixDateTime, CreatedUnixDateTime) " +
                    "values (?, ?, ?, ?, ?) " +
                    "on conflict(ChunkPosX, ChunkPosZ) do update set " +
                    "ChunkHash = excluded.ChunkHash, " +
                    "LastModifiedUnixDateTime = excluded.LastModifiedUnixDateTime"
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

    @Override
    public Optional<Integer> getChunkHash(int levelId, int posX, int posZ) {
        return withConnection(levelId, connection -> {
            try (PreparedStatement preparedStatement = connection.prepareStatement(
                    "select ChunkHash from ChunkHash where ChunkPosX = ? and ChunkPosZ = ?"
            )) {
                preparedStatement.setInt(1, posX);
                preparedStatement.setInt(2, posZ);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    if (!resultSet.next())
                        return Optional.empty();

                    return Optional.of(resultSet.getInt("ChunkHash"));
                }
            }
        });
    }

    @Override
    public void setBeaconBeam(int levelId, int blockPosX, int blockPosY, int blockPosZ, int colourR, int colourG, int colourB) {
        withConnection(levelId, connection -> {
            try (PreparedStatement preparedStatement = connection.prepareStatement(
                    "insert into BeaconBeam (BlockPosX, BlockPosY, BlockPosZ, ColorR, ColorG, ColorB, LastModifiedUnixDateTime, CreatedUnixDateTime) " +
                    "values (?, ?, ?, ?, ?, ?, ?, ?) " +
                    "on conflict(BlockPosX, BlockPosY, BlockPosZ) do update set " +
                    "ColorR = excluded.ColorR, " +
                    "ColorG = excluded.ColorG, " +
                    "ColorB = excluded.ColorB, " +
                    "LastModifiedUnixDateTime = excluded.LastModifiedUnixDateTime"
            )) {
                preparedStatement.setInt(1, blockPosX);
                preparedStatement.setInt(2, blockPosY);
                preparedStatement.setInt(3, blockPosZ);
                preparedStatement.setInt(4, colourR);
                preparedStatement.setInt(5, colourG);
                preparedStatement.setInt(6, colourB);
                preparedStatement.setTimestamp(7, Timestamp.from(Instant.now()));
                preparedStatement.setTimestamp(8, Timestamp.from(Instant.now()));
                return preparedStatement.executeUpdate();
            }
        });
    }

    @Override
    public boolean deleteBeaconBeam(int levelId, int blockPosX, int blockPosY, int blockPosZ) {
        return withConnection(levelId, connection -> {
            try (PreparedStatement preparedStatement = connection.prepareStatement(
                    "delete from BeaconBeam where BlockPosX = ? and BlockPosY = ? and BlockPosZ = ?"
            )) {
                preparedStatement.setInt(1, blockPosX);
                preparedStatement.setInt(2, blockPosY);
                preparedStatement.setInt(3, blockPosZ);
                return preparedStatement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public List<BeaconBeam> listBeaconBeamsInArea(int levelId, int minBlockPosX, int minBlockPosZ, int maxBlockPosX, int maxBlockPosZ) {
        return withConnection(levelId, connection -> {
            try (PreparedStatement preparedStatement = connection.prepareStatement(
                    "select * from BeaconBeam where " +
                    "BlockPosX >= ? and BlockPosZ >= ? and " +
                    "BlockPosX <= ? and BlockPosZ <= ?"
            )) {
                preparedStatement.setInt(1, minBlockPosX);
                preparedStatement.setInt(2, minBlockPosZ);
                preparedStatement.setInt(3, maxBlockPosX);
                preparedStatement.setInt(4, maxBlockPosZ);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    List<BeaconBeam> beaconBeams = new ArrayList<>();
                    while (resultSet.next()) {
                        BeaconBeam beam = new BeaconBeam();
                        beam.levelId = levelId;
                        beam.blockPosX = resultSet.getInt("BlockPosX");
                        beam.blockPosY = resultSet.getInt("BlockPosY");
                        beam.blockPosZ = resultSet.getInt("BlockPosZ");
                        beam.colourR = resultSet.getInt("ColorR");
                        beam.colourG = resultSet.getInt("ColorG");
                        beam.colourB = resultSet.getInt("ColorB");
                        beam.lastModified = resultSet.getTimestamp("LastModifiedUnixDateTime");
                        beam.created = resultSet.getTimestamp("CreatedUnixDateTime");
                        beaconBeams.add(beam);
                    }

                    return beaconBeams;
                }
            }
        });
    }

    @Override
    public void setFullData(FullData fullData) {
        ByteArrayOutputStream compressedData = new ByteArrayOutputStream();
        ByteArrayOutputStream compressedColumnGenerationStep = new ByteArrayOutputStream();
        ByteArrayOutputStream compressedColumnWorldCompressionMode = new ByteArrayOutputStream();
        ByteArrayOutputStream compressedMapping = new ByteArrayOutputStream();

        try {
            try (OutputStream dataOutputStream = new DhDataOutputStream(compressedData, compressionMode)) {
                dataOutputStream.write(fullData.data);
            }
            compressedData.close();

            try (OutputStream dataOutputStream = new DhDataOutputStream(compressedColumnGenerationStep, compressionMode)) {
                dataOutputStream.write(fullData.columnGenerationStep);
            }
            compressedColumnGenerationStep.close();

            try (OutputStream dataOutputStream = new DhDataOutputStream(compressedColumnWorldCompressionMode, compressionMode)) {
                dataOutputStream.write(fullData.columnWorldCompressionMode);
            }
            compressedColumnWorldCompressionMode.close();

            try (OutputStream dataOutputStream = new DhDataOutputStream(compressedMapping, compressionMode)) {
                dataOutputStream.write(fullData.mapping);
            }
            compressedMapping.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        withConnection(fullData.levelId, connection -> {
            try (PreparedStatement preparedStatement = connection.prepareStatement(
                    "insert into FullData (" +
                    "DetailLevel, " +
                    "PosX, " +
                    "PosZ, " +
                    "MinY, " +
                    "DataChecksum, " +
                    "Data, " +
                    "ColumnGenerationStep, " +
                    "ColumnWorldCompressionMode, " +
                    "Mapping, " +
                    "DataFormatVersion, " +
                    "CompressionMode, " +
                    "ApplyToParent, " +
                    "LastModifiedUnixDateTime, " +
                    "CreatedUnixDateTime" +
                    ") values (?,?,?,?,?,?,?,?,?,?,?,?,?,?) on conflict (DetailLevel, PosX, PosZ) do update set " +
                    "MinY = excluded.MinY, " +
                    "DataChecksum = excluded.DataChecksum, " +
                    "Data = excluded.Data, " +
                    "ColumnGenerationStep = excluded.ColumnGenerationStep, " +
                    "ColumnWorldCompressionMode = excluded.ColumnWorldCompressionMode, " +
                    "Mapping = excluded.Mapping, " +
                    "DataFormatVersion = excluded.DataFormatVersion, " +
                    "CompressionMode = excluded.CompressionMode, " +
                    "ApplyToParent = excluded.ApplyToParent, " +
                    "LastModifiedUnixDateTime = excluded.LastModifiedUnixDateTime"
            )) {
                preparedStatement.setByte(1, (byte) (fullData.detailLevel - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL));
                preparedStatement.setInt(2, fullData.posX);
                preparedStatement.setInt(3, fullData.posZ);
                preparedStatement.setInt(4, fullData.minY);
                preparedStatement.setInt(5, fullData.dataChecksum);
                preparedStatement.setBytes(6, compressedData.toByteArray());
                preparedStatement.setBytes(7, compressedColumnGenerationStep.toByteArray());
                preparedStatement.setBytes(8, compressedColumnWorldCompressionMode.toByteArray());
                preparedStatement.setBytes(9, compressedMapping.toByteArray());
                preparedStatement.setByte(10, fullData.dataFormatVersion);
                preparedStatement.setByte(11, compressionMode.value);
                preparedStatement.setBoolean(12, fullData.applyToParent);
                preparedStatement.setTimestamp(13, Timestamp.from(Instant.now()));
                preparedStatement.setTimestamp(14, Timestamp.from(Instant.now()));
                return preparedStatement.executeUpdate();
            }
        });
    }

    @Override
    public Optional<FullData> getFullData(int levelId, byte detailLevel, int posX, int posZ) {
        return withConnection(levelId, connection -> {
            try (PreparedStatement preparedStatement = connection.prepareStatement(
                    "select " +
                    "DetailLevel, " +
                    "PosX, " +
                    "PosZ, " +
                    "MinY, " +
                    "DataChecksum, " +
                    "Data, " +
                    "ColumnGenerationStep, " +
                    "ColumnWorldCompressionMode, " +
                    "Mapping, " +
                    "DataFormatVersion, " +
                    "CompressionMode, " +
                    "ApplyToParent, " +
                    "LastModifiedUnixDateTime, " +
                    "CreatedUnixDateTime " +
                    "from FullData where DetailLevel = ? and PosX = ? and PosZ = ?"
            )) {
                preparedStatement.setByte(1, (byte) (detailLevel - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL));
                preparedStatement.setInt(2, posX);
                preparedStatement.setInt(3, posZ);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    if (!resultSet.next())
                        return Optional.empty();

                    EDhApiDataCompressionMode compressionMode;
                    try {
                        compressionMode = EDhApiDataCompressionMode.getFromValue(resultSet.getByte("CompressionMode"));
                    } catch (IllegalArgumentException e) {
                        // Could happen if a compression mode is removed
                        return Optional.empty();
                    }

                    FullData data = new FullData();
                    data.levelId = levelId;
                    data.detailLevel = (byte) (resultSet.getByte("DetailLevel") + DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
                    data.posX = resultSet.getInt("PosX");
                    data.posZ = resultSet.getInt("PosZ");
                    data.minY = resultSet.getInt("MinY");
                    data.dataChecksum = resultSet.getInt("DataChecksum");
                    data.data = readAllBytes(new DhDataInputStream(resultSet.getBinaryStream("Data"), compressionMode));
                    data.columnGenerationStep = readAllBytes(new DhDataInputStream(resultSet.getBinaryStream("ColumnGenerationStep"), compressionMode));
                    data.columnWorldCompressionMode = readAllBytes(new DhDataInputStream(resultSet.getBinaryStream("ColumnWorldCompressionMode"), compressionMode));
                    data.mapping = readAllBytes(new DhDataInputStream(resultSet.getBinaryStream("Mapping"), compressionMode));
                    data.dataFormatVersion = resultSet.getByte("DataFormatVersion");
                    data.applyToParent = resultSet.getBoolean("ApplyToParent");
                    data.lastModified = resultSet.getTimestamp("LastModifiedUnixDateTime");
                    data.created = resultSet.getTimestamp("CreatedUnixDateTime");
                    return Optional.of(data);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });

    }

    @Override
    public Optional<FullDataMetadata> getFullDataMetadata(int levelId, byte detailLevel, int posX, int posZ) {
        return withConnection(levelId, connection -> {
            try (PreparedStatement preparedStatement = connection.prepareStatement(
                    "select " +
                    "DetailLevel, " +
                    "PosX, " +
                    "PosZ, " +
                    "MinY, " +
                    "DataChecksum, " +
                    "ColumnGenerationStep, " +
                    "ColumnWorldCompressionMode, " +
                    "DataFormatVersion, " +
                    "CompressionMode, " +
                    "ApplyToParent, " +
                    "LastModifiedUnixDateTime, " +
                    "CreatedUnixDateTime " +
                    "from FullData where DetailLevel = ? and PosX = ? and PosZ = ?"
            )) {
                preparedStatement.setByte(1, (byte) (detailLevel - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL));
                preparedStatement.setInt(2, posX);
                preparedStatement.setInt(3, posZ);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    if (!resultSet.next())
                        return Optional.empty();

                    EDhApiDataCompressionMode compressionMode;
                    try {
                        compressionMode = EDhApiDataCompressionMode.getFromValue(resultSet.getByte("CompressionMode"));
                    } catch (IllegalArgumentException e) {
                        // Could happen if a compression mode is removed
                        return Optional.empty();
                    }

                    FullDataMetadata data = new FullDataMetadata();
                    data.levelId = levelId;
                    data.detailLevel = (byte) (resultSet.getByte("DetailLevel") + DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
                    data.posX = resultSet.getInt("PosX");
                    data.posZ = resultSet.getInt("PosZ");
                    data.minY = resultSet.getInt("MinY");
                    data.dataChecksum = resultSet.getInt("DataChecksum");
                    data.columnGenerationStep = readAllBytes(new DhDataInputStream(resultSet.getBinaryStream("ColumnGenerationStep"), compressionMode));
                    data.columnWorldCompressionMode = readAllBytes(new DhDataInputStream(resultSet.getBinaryStream("ColumnWorldCompressionMode"), compressionMode));
                    data.dataFormatVersion = resultSet.getByte("DataFormatVersion");
                    data.applyToParent = resultSet.getBoolean("ApplyToParent");
                    data.lastModified = resultSet.getTimestamp("LastModifiedUnixDateTime");
                    data.created = resultSet.getTimestamp("CreatedUnixDateTime");
                    return Optional.of(data);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    @Override
    public boolean updateFullDataApplyToParent(int levelId, byte detailLevel, int posX, int posZ, boolean applyToParent) {
        return withConnection(levelId, connection -> {
            try (PreparedStatement preparedStatement = connection.prepareStatement(
                    "update FullData set ApplyToParent = ? where DetailLevel = ? and PosX = ? and PosZ = ?"
            )) {
                preparedStatement.setBoolean(1, applyToParent);
                preparedStatement.setByte(2, (byte) (detailLevel - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL));
                preparedStatement.setInt(3, posX);
                preparedStatement.setInt(4, posZ);
                return preparedStatement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public boolean deleteFullData(int levelId, byte detailLevel, int posX, int posZ) {
        return withConnection(levelId, connection -> {
            try (PreparedStatement preparedStatement = connection.prepareStatement(
                    "delete from FullData where DetailLevel = ? and PosX = ? and PosZ = ?"
            )) {
                preparedStatement.setByte(1, (byte) (detailLevel - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL));
                preparedStatement.setInt(2, posX);
                preparedStatement.setInt(3, posZ);
                return preparedStatement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public List<FullDataPosition> getFullDataPositionsToUpdate(int levelId, int posX, int posZ, int returnCount) {
        return withConnection(levelId, connection -> {
            try (PreparedStatement preparedStatement = connection.prepareStatement(
                    "select DetailLevel, PosX, PosZ, (pow(PosX - ?, 2) + pow(PosZ - ?, 2)) as Distance " +
                    "from FullData where ApplyToParent = true order by Distance limit ?"
            )) {
                preparedStatement.setInt(1, posX);
                preparedStatement.setInt(2, posZ);
                preparedStatement.setInt(3, returnCount);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    List<FullDataPosition> positions = new ArrayList<>();
                    while (resultSet.next()) {
                        FullDataPosition position = new FullDataPosition();
                        position.levelId = levelId;
                        position.detailLevel = (byte) (resultSet.getByte("DetailLevel") + DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
                        position.posX = resultSet.getInt("PosX");
                        position.posZ = resultSet.getInt("PosZ");
                        positions.add(position);
                    }
                    return positions;
                }
            }
        });
    }

    /**
     * getAllFullDataPositions returns all FullData positions stored in the datastore.
     * it is used by the compression test, and is nto part of the API.
     *
     * @param levelId unique id of the level. See {@link #ensureLevel}
     * @return list of positions.
     */
    public List<FullDataPosition> getAllFullDataPositions(int levelId) {
        return withConnection(levelId, connection -> {
            try (PreparedStatement preparedStatement = connection.prepareStatement(
                    "select DetailLevel, PosX, PosZ from FullData"
            )) {
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    List<FullDataPosition> positions = new ArrayList<>();
                    while (resultSet.next()) {
                        FullDataPosition position = new FullDataPosition();
                        position.levelId = levelId;
                        position.detailLevel = (byte) (resultSet.getByte("DetailLevel") + DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
                        position.posX = resultSet.getInt("PosX");
                        position.posZ = resultSet.getInt("PosZ");
                        positions.add(position);
                    }
                    return positions;
                }
            }
        });
    }

    @Deprecated /// replace with inputStream.readAllBytes() when java 8 support is dropped.
    private byte[] readAllBytes(InputStream inputStream) throws IOException {
        byte[] buff = new byte[8 * (1 << 10)];
        int read;

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            while ((read = inputStream.read(buff, 0, buff.length)) >= 0) {
                outputStream.write(buff, 0, read);
            }

            return outputStream.toByteArray();
        } finally {
            inputStream.close();
        }
    }

}
