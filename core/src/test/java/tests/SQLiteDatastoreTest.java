package tests;

import com.seibel.distanthorizons.api.interfaces.override.datastore.IDhApiDatastore;
import com.seibel.distanthorizons.core.sql.SQLiteDatastore;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.util.List;

/**
 * Tests for {@link SQLiteDatastore}
 */
public class SQLiteDatastoreTest {
    public static String DATABASE_TYPE = "jdbc:sqlite";
    public static String LEVEL_NAME = new File("test.sqlite").getAbsolutePath();
    private SQLiteDatastore datastore = SQLiteDatastore.SINGLETON;

    @BeforeClass
    public static void testSetup() {
        File dbFile = new File(LEVEL_NAME);
        if (dbFile.exists()) {
            Assert.assertTrue("unable to delete old test DB File.", dbFile.delete());
        }
    }

    @Test
    public void canSetAndGetChunkHash() {
        int levelId = datastore.ensureLevel(LEVEL_NAME);

        datastore.setChunkHash(levelId, 1, 2, 20);
        assert datastore.getChunkHash(levelId, 1, 2) == 20;
    }

    @Test(expected = RuntimeException.class)
    public void setChunkHashValidatesLevelId() {
        int levelId = datastore.ensureLevel(LEVEL_NAME);

        datastore.setChunkHash(levelId + 1, 1, 2, 20);
    }

    @Test(expected = RuntimeException.class)
    public void getChunkHashValidatesLevelId() {
        int levelId = datastore.ensureLevel(LEVEL_NAME);

        datastore.setChunkHash(levelId, 1, 2, 20);
        datastore.getChunkHash(levelId + 1, 1, 2);
    }

    @Test
    public void canSetAndGetBeaconBeamsInArea() {
        int levelId = datastore.ensureLevel(LEVEL_NAME);

        datastore.setBeaconBeam(levelId, -1, 200, 1, 255, 255, 0);
        datastore.setBeaconBeam(levelId, 100, 1, 0, 255, 2, 0);
        datastore.setBeaconBeam(levelId, 0, 1, -100, 255, 2, 0);
        List<IDhApiDatastore.BeaconBeam> beams = datastore.getBeaconBeamsInArea(levelId, -1, 0, 99, 100);
        assert beams.size() == 1;
        IDhApiDatastore.BeaconBeam beam = beams.get(0);
        assert beam.levelId == levelId;
        assert beam.blockPosX == -1;
        assert beam.blockPosY == 200;
        assert beam.blockPosZ == 1;
        assert beam.colourR == 255;
        assert beam.colourG == 255;
        assert beam.colourB == 0;
    }
}
