package com.seibel.distanthorizons.api.interfaces.override.datastore;

import com.seibel.distanthorizons.api.interfaces.override.IDhApiOverrideable;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * IDhApiDatastore provides an interface which Distant Horizons uses to store LOD data.
 * <p>
 * Traditionally an SQLite database. The motivation behind this API is dedicated database support
 * with the hope that even more data storage methods would also be possible to add in the future.
 * </p> <p>
 * Implementers should internally handle transient errors, and any quirks of the given storage method.
 * </p> <p>
 * IDhApiDatastore is a singleton. I've gone back and forth on this a few times,
 * and I've settled on the opinion that session management is out of scope for IDhApiDatastore.
 * My primary justification is not wanting to have a IDhApiDatastoreFactory that inevitably fails to
 * concisely cover all use cases and pollute the API with methods only used by a single concrete implementation.
 * </p>
 * Standard method prefixes are
 * <ol>
 * <li><code>ensure*</code> Creates the item if it does not exist, does nothing if the item already exists. Returns the item in either case.</li>
 * <li><code>set*</code> Creates the item if it does not exist, updating it in place if it does not exist. Returns void.</li>
 * <li><code>update*</code> Updates the item in place. Returns true if the item existed.</li>
 * <li><code>get*</code> Fetches a single item. Returns an {@link Optional} with the result.</li>
 * <li><code>list*</code> Fetches many items. Returns a {@link List} with results.</li>
 * <li><code>delete*</code> Removes an item. Returns true if the item existed.</li>
 * <li><code>purge*</code> Removes an item and all data associated with it. Returns true if the item existed.</li>
 * </ol>
 */
public interface IDhApiDatastore extends IDhApiOverrideable {
    /**
     * ensureLevel creates the level if it does not already exist.
     *
     * @param uniqueLevelName is a human-readable string that is unique to the level.
     * @return the levelId used by the datastore.
     */
    int ensureLevel(String uniqueLevelName);

    /**
     * setChunkHash sets the chunkHash for the given level and position, updating if it already exists.
     *
     * @param levelId   unique id of the level. See {@link #ensureLevel}
     * @param chunkHash hash value
     */
    void setChunkHash(int levelId, int chunkPosX, int chunkPosZ, int chunkHash);

    /**
     * getChunkHash returns the hash for the chunk at the given position, returning 0 if it doesn't exist.
     *
     * @param levelId unique id of the level. See {@link #ensureLevel}
     * @return hash value
     */
    Optional<Integer> getChunkHash(int levelId, int chunkPosX, int chunkPosZ);

    /**
     * setBeaconBeam sets the BeaconBeam, updating if it already exists.
     *
     * @param levelId unique id of the level. See {@link #ensureLevel}
     */
    void setBeaconBeam(int levelId, int blockPosX, int blockPosY, int blockPosZ, int colourR, int colourG, int colourB);

    /**
     * deleteBeaconBeam removed the given BeaconBeam.
     *
     * @param levelId unique id of the level. See {@link #ensureLevel}
     * @return true if the BeaconBeam existed
     */
    boolean deleteBeaconBeam(int levelId, int blockPosX, int blockPosY, int blockPosZ);

    /**
     * listBeaconBeamsInArea returns the beacon beams that exist within the given X, Z area at any Y.
     *
     * @param levelId      unique id of the level. See {@link #ensureLevel}
     * @param minBlockPosX bounding box min X
     * @param minBlockPosZ bounding box min Z
     * @param maxBlockPosX bounding box max X
     * @param maxBlockPosZ bounding box max Z
     * @return list of {@link BeaconBeam} within the given area
     */
    List<BeaconBeam> listBeaconBeamsInArea(int levelId, int minBlockPosX, int minBlockPosZ, int maxBlockPosX, int maxBlockPosZ);

    class BeaconBeam {
        public int levelId;
        public int blockPosX;
        public int blockPosY;
        public int blockPosZ;
        public int colourR;
        public int colourG;
        public int colourB;
        public Timestamp lastModified;
        public Timestamp created;

    }

    /**
     * setFullData sets FullData, updating if it already exists.
     */
    void setFullData(FullData fullData);

    /**
     * getFullData returns FullData for the given location.
     *
     * @param levelId unique id of the level. See {@link #ensureLevel}
     * @return {@link FullData}
     */
    Optional<FullData> getFullData(int levelId, byte detailLevel, int posX, int posZ);

    class FullData {
        public int levelId;
        public byte detailLevel;
        public int posX;
        public int posZ;
        public int minY;
        public int dataChecksum;
        public byte[] data;
        public byte[] columnGenerationStep;
        public byte[] columnWorldCompressionMode;
        public byte[] mapping;
        public byte dataFormatVersion;
        public boolean applyToParent;
        public Timestamp lastModified;
        public Timestamp created;

    }

    /**
     * getFullDataMetadata returns FullData with the Data and Mapping fields left unpopulated.
     * It is motivated by users that don't need all data and want to avoid the overhead of fetching it unnecessarily.
     *
     * @param levelId     unique id of the level. See {@link #ensureLevel}
     * @param detailLevel detail level to get
     * @param posX        section X position
     * @param posZ        section Z position
     * @return {@link FullDataMetadata}
     */
    Optional<FullDataMetadata> getFullDataMetadata(int levelId, byte detailLevel, int posX, int posZ);

    class FullDataMetadata {
        public int levelId;
        public byte detailLevel;
        public int posX;
        public int posZ;
        public int minY;
        public int dataChecksum;
        public byte[] columnGenerationStep;
        public byte[] columnWorldCompressionMode;
        public byte dataFormatVersion;
        public boolean applyToParent;
        public Timestamp lastModified;
        public Timestamp created;

    }

    /**
     * updateFullDataApplyToParent sets the ApplyToParent field in FullData.
     *
     * @param levelId       unique id of the level. See {@link #ensureLevel}
     * @param detailLevel   detail level to get
     * @param posX          section X position
     * @param posZ          section Z position
     * @param applyToParent value to set ApplyToParent to
     * @return true if FullData existed
     */
    boolean updateFullDataApplyToParent(int levelId, byte detailLevel, int posX, int posZ, boolean applyToParent);

    /**
     * deleteFullData removes FullData from the datastore.
     *
     * @param levelId     unique id of the level. See {@link #ensureLevel}
     * @param detailLevel detail level to get
     * @param posX        section X position
     * @param posZ        section Z position
     * @return true if FullData existed
     */
    boolean deleteFullData(int levelId, byte detailLevel, int posX, int posZ);

    /**
     * getFullDataPositionsToUpdate returns the locations of FullData instances that are close to the given location,
     * ordered by distance and where ApplyToParent is true.
     *
     * @param levelId     unique id of the level. See {@link #ensureLevel}
     * @param posX        section X position
     * @param posZ        section Z position
     * @param returnCount number of results to return
     * @return list of {@link FullDataPosition}
     */
    List<FullDataPosition> getFullDataPositionsToUpdate(int levelId, int posX, int posZ, int returnCount);

    class FullDataPosition {
        public int levelId;
        public byte detailLevel;
        public int posX;
        public int posZ;

    }

}
