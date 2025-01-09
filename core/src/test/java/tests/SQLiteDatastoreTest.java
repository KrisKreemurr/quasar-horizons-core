package tests;

import com.seibel.distanthorizons.core.sql.SQLiteDatastore;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;

/**
 * Tests for {@link SQLiteDatastore}
 */
public class SQLiteDatastoreTest {
    public static String DATABASE_TYPE = "jdbc:sqlite";
    public static String DB_FILE_NAME = "test.sqlite";

    @BeforeClass
    public static void testSetup() {
        File dbFile = new File(DB_FILE_NAME);
        if (dbFile.exists()) {
            Assert.assertTrue("unable to delete old test DB File.", dbFile.delete());
        }
    }

    @Test
    public void testCanCreateLevel() {
        File dbFile = new File(DB_FILE_NAME);
        SQLiteDatastore datastore = SQLiteDatastore.SINGLETON;

        datastore.ensureLevel(dbFile.getAbsolutePath());

        assert dbFile.exists();
    }
}
