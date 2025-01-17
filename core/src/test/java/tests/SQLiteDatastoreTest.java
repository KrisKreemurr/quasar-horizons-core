package tests;

import com.seibel.distanthorizons.api.enums.config.EDhApiDataCompressionMode;
import com.seibel.distanthorizons.api.interfaces.override.datastore.IDhApiDatastore;
import com.seibel.distanthorizons.core.sql.SQLiteDatastore;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.util.List;
import java.util.Optional;

/**
 * Tests for {@link SQLiteDatastore}
 */
public class SQLiteDatastoreTest
{
	public static String DATABASE_TYPE = "jdbc:sqlite";
	public static String LEVEL_NAME = new File("test.sqlite").getAbsolutePath();
	private final SQLiteDatastore datastore = new SQLiteDatastore(EDhApiDataCompressionMode.LZ4);
	
	@BeforeClass
	public static void testSetup()
	{
		File dbFile = new File(LEVEL_NAME);
		if (dbFile.exists())
		{
			Assert.assertTrue("unable to delete old test DB File.", dbFile.delete());
		}
	}
	
	@Test
	public void canSetAndGetChunkHash()
	{
		int levelId = datastore.ensureLevel(LEVEL_NAME);
		
		datastore.setChunkHash(levelId, 1, 2, 20);
		Assert.assertEquals(Optional.of(20), datastore.getChunkHash(levelId, 1, 2));
	}
	
	@Test
	public void setChunkHashValidatesLevelId()
	{
		int levelId = datastore.ensureLevel(LEVEL_NAME);
		
		Assert.assertThrows(RuntimeException.class, () -> {
			datastore.setChunkHash(levelId + 1, 1, 2, 20);
		});
	}
	
	@Test
	public void getChunkHashValidatesLevelId()
	{
		int levelId = datastore.ensureLevel(LEVEL_NAME);
		
		datastore.setChunkHash(levelId, 1, 2, 20);
		
		Assert.assertThrows(RuntimeException.class, () -> {
			datastore.getChunkHash(levelId + 1, 1, 2);
		});
	}
	
	@Test
	public void canSetAndListBeaconBeamsInArea()
	{
		int levelId = datastore.ensureLevel(LEVEL_NAME);
		
		datastore.setBeaconBeam(levelId, -1, 200, 1, 255, 255, 0);
		datastore.setBeaconBeam(levelId, 100, 1, 0, 255, 2, 0);
		datastore.setBeaconBeam(levelId, 0, 1, -100, 255, 2, 0);
		List<IDhApiDatastore.BeaconBeam> beams = datastore.listBeaconBeamsInArea(levelId, -1, 0, 99, 100);
		Assert.assertEquals(1, beams.size());
		IDhApiDatastore.BeaconBeam beam = beams.get(0);
		Assert.assertEquals(levelId, beam.levelId);
		Assert.assertEquals(-1, beam.blockPosX);
		Assert.assertEquals(200, beam.blockPosY);
		Assert.assertEquals(1, beam.blockPosZ);
		Assert.assertEquals(255, beam.colourR);
		Assert.assertEquals(255, beam.colourG);
		Assert.assertEquals(0, beam.colourB);
	}
	
	@Test
	public void canGetAndSetFullData()
	{
		int levelId = datastore.ensureLevel(LEVEL_NAME);
		
		IDhApiDatastore.FullData fullData = new IDhApiDatastore.FullData();
		fullData.levelId = levelId;
		fullData.detailLevel = 6;
		fullData.posX = 4;
		fullData.posZ = 5;
		fullData.minY = 64;
		fullData.dataChecksum = 42;
		fullData.data = new byte[]{1, 2, 3, 5, 7, 23, 7, 1, 6, 12, 76, 35, 1, 65};
		fullData.columnGenerationStep = new byte[]{1, 2, 3};
		fullData.columnWorldCompressionMode = new byte[]{2, 3, 1};
		fullData.mapping = new byte[]{ };
		fullData.dataFormatVersion = 1;
		fullData.applyToParent = false;
		datastore.setFullData(fullData);
		
		Optional<IDhApiDatastore.FullData> fetchedFullData = datastore.getFullData(levelId, (byte) 6, 4, 5);
		Assert.assertTrue(fetchedFullData.isPresent());
		Assert.assertEquals(fullData.levelId, fetchedFullData.get().levelId);
		Assert.assertEquals(fullData.detailLevel, fetchedFullData.get().detailLevel);
		Assert.assertEquals(fullData.posX, fetchedFullData.get().posX);
		Assert.assertEquals(fullData.posZ, fetchedFullData.get().posZ);
		Assert.assertEquals(fullData.minY, fetchedFullData.get().minY);
		Assert.assertEquals(fullData.dataChecksum, fetchedFullData.get().dataChecksum);
		Assert.assertArrayEquals(fullData.data, fetchedFullData.get().data);
		Assert.assertArrayEquals(fullData.columnGenerationStep, fetchedFullData.get().columnGenerationStep);
		Assert.assertArrayEquals(fullData.columnWorldCompressionMode, fetchedFullData.get().columnWorldCompressionMode);
		Assert.assertArrayEquals(fullData.mapping, fetchedFullData.get().mapping);
		Assert.assertEquals(fullData.dataFormatVersion, fetchedFullData.get().dataFormatVersion);
		Assert.assertEquals(fullData.applyToParent, fetchedFullData.get().applyToParent);
	}
	
	@Test
	public void canGetFullDataMetadata()
	{
		int levelId = datastore.ensureLevel(LEVEL_NAME);
		
		IDhApiDatastore.FullData fullData = new IDhApiDatastore.FullData();
		fullData.levelId = levelId;
		fullData.detailLevel = 12;
		fullData.posX = 5;
		fullData.posZ = 6;
		fullData.minY = 64;
		fullData.dataChecksum = 42;
		fullData.data = new byte[]{1, 2, 3, 5, 7, 23, 7, 1, 6, 12, 76, 35, 1, 65};
		fullData.columnGenerationStep = new byte[]{1, 2, 3};
		fullData.columnWorldCompressionMode = new byte[]{2, 3, 1};
		fullData.mapping = new byte[]{ };
		fullData.dataFormatVersion = 1;
		fullData.applyToParent = false;
		datastore.setFullData(fullData);
		
		Optional<IDhApiDatastore.FullDataMetadata> fetchedFullDataMetadata = datastore.getFullDataMetadata(levelId, (byte) 12, 5, 6);
		Assert.assertTrue(fetchedFullDataMetadata.isPresent());
		Assert.assertEquals(fullData.levelId, fetchedFullDataMetadata.get().levelId);
		Assert.assertEquals(fullData.detailLevel, fetchedFullDataMetadata.get().detailLevel);
		Assert.assertEquals(fullData.posX, fetchedFullDataMetadata.get().posX);
		Assert.assertEquals(fullData.posZ, fetchedFullDataMetadata.get().posZ);
		Assert.assertEquals(fullData.minY, fetchedFullDataMetadata.get().minY);
		Assert.assertEquals(fullData.dataChecksum, fetchedFullDataMetadata.get().dataChecksum);
		Assert.assertArrayEquals(fullData.columnGenerationStep, fetchedFullDataMetadata.get().columnGenerationStep);
		Assert.assertArrayEquals(fullData.columnWorldCompressionMode, fetchedFullDataMetadata.get().columnWorldCompressionMode);
		Assert.assertEquals(fullData.dataFormatVersion, fetchedFullDataMetadata.get().dataFormatVersion);
		Assert.assertEquals(fullData.applyToParent, fetchedFullDataMetadata.get().applyToParent);
	}
	
	@Test
	public void canDeleteFullData()
	{
		int levelId = datastore.ensureLevel(LEVEL_NAME);
		
		IDhApiDatastore.FullData fullData = new IDhApiDatastore.FullData();
		fullData.levelId = levelId;
		fullData.detailLevel = 12;
		fullData.posX = -45;
		fullData.posZ = 6;
		fullData.minY = 64;
		fullData.dataChecksum = 42;
		fullData.data = new byte[]{1, 2, 3, 5, 7, 23, 7, 1, 6, 12, 76, 35, 1, 65};
		fullData.columnGenerationStep = new byte[]{1, 2, 3};
		fullData.columnWorldCompressionMode = new byte[]{2, 3, 1};
		fullData.mapping = new byte[]{ };
		fullData.dataFormatVersion = 1;
		fullData.applyToParent = false;
		datastore.setFullData(fullData);
		
		Optional<IDhApiDatastore.FullDataMetadata> fetchedFullDataMetadata = datastore.getFullDataMetadata(levelId, (byte) 12, -45, 6);
		Assert.assertTrue(fetchedFullDataMetadata.isPresent());
		Assert.assertTrue(datastore.deleteFullData(levelId, (byte) 12, -45, 6));
		fetchedFullDataMetadata = datastore.getFullDataMetadata(levelId, (byte) 12, -45, 6);
		Assert.assertFalse(fetchedFullDataMetadata.isPresent());
	}
	
	
	@Test
	public void canGetFullDatPositionsToUpdate()
	{
		int levelId = datastore.ensureLevel(LEVEL_NAME);
		
		IDhApiDatastore.FullData fullData = new IDhApiDatastore.FullData();
		fullData.levelId = levelId;
		fullData.detailLevel = 12;
		fullData.posX = 576000;
		fullData.posZ = 2;
		fullData.minY = 64;
		fullData.dataChecksum = 42;
		fullData.data = new byte[]{1, 2, 3, 5, 7, 23, 7, 1, 6, 12, 76, 35, 1, 65};
		fullData.columnGenerationStep = new byte[]{1, 2, 3};
		fullData.columnWorldCompressionMode = new byte[]{2, 3, 1};
		fullData.mapping = new byte[]{ };
		fullData.dataFormatVersion = 1;
		fullData.applyToParent = false;
		datastore.setFullData(fullData);
		
		fullData.levelId = levelId;
		fullData.detailLevel = 11;
		fullData.posX = 576000;
		fullData.posZ = 4;
		fullData.minY = 64;
		fullData.dataChecksum = 42;
		fullData.data = new byte[]{1, 2, 3, 5, 7, 23, 7, 1, 6, 12, 76, 35, 1, 65};
		fullData.columnGenerationStep = new byte[]{1, 2, 3};
		fullData.columnWorldCompressionMode = new byte[]{2, 3, 1};
		fullData.mapping = new byte[]{ };
		fullData.dataFormatVersion = 1;
		fullData.applyToParent = true;
		datastore.setFullData(fullData);
		
		fullData.levelId = levelId;
		fullData.detailLevel = 11;
		fullData.posX = 576000;
		fullData.posZ = 3;
		fullData.minY = 64;
		fullData.dataChecksum = 42;
		fullData.data = new byte[]{1, 2, 3, 5, 7, 23, 7, 1, 6, 12, 76, 35, 1, 65};
		fullData.columnGenerationStep = new byte[]{1, 2, 3};
		fullData.columnWorldCompressionMode = new byte[]{2, 3, 1};
		fullData.mapping = new byte[]{ };
		fullData.dataFormatVersion = 1;
		fullData.applyToParent = true;
		datastore.setFullData(fullData);
		
		List<IDhApiDatastore.FullDataPosition> fullDataPositions = datastore.getFullDataPositionsToUpdate(levelId, 0, 2, 3);
		Assert.assertTrue(fullDataPositions.stream().anyMatch(pos ->
				pos.detailLevel == 11 &&
						pos.posX == 576000 &&
						pos.posZ == 4
		));
		
		Assert.assertTrue(fullDataPositions.stream().anyMatch(pos ->
				pos.detailLevel == 11 &&
						pos.posX == 576000 &&
						pos.posZ == 4
		));
		
		Assert.assertTrue(fullDataPositions.stream().noneMatch(pos ->
				pos.detailLevel == 12 &&
						pos.posX == 576000 &&
						pos.posZ == 2
		));
	}
	
}
