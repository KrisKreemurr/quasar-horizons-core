package tests;

import com.seibel.distanthorizons.api.interfaces.override.datastore.IDhApiDatastore;
import com.seibel.distanthorizons.core.sql.SQLiteDatastore;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Tests for {@link SQLiteDatastore}
 */
public class SQLiteDatastoreTest
{
	public static String DATABASE_TYPE = "jdbc:sqlite";
	public static String LEVEL_NAME = new File("test.sqlite").getAbsolutePath();
	private final SQLiteDatastore datastore = SQLiteDatastore.SINGLETON;
	
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
		assert datastore.getChunkHash(levelId, 1, 2) == 20;
	}
	
	@Test(expected = RuntimeException.class)
	public void setChunkHashValidatesLevelId()
	{
		int levelId = datastore.ensureLevel(LEVEL_NAME);
		
		datastore.setChunkHash(levelId + 1, 1, 2, 20);
	}
	
	@Test(expected = RuntimeException.class)
	public void getChunkHashValidatesLevelId()
	{
		int levelId = datastore.ensureLevel(LEVEL_NAME);
		
		datastore.setChunkHash(levelId, 1, 2, 20);
		datastore.getChunkHash(levelId + 1, 1, 2);
	}
	
	@Test
	public void canSetAndGetBeaconBeamsInArea()
	{
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
		fullData.compressionMode = 2;
		fullData.applyToParent = false;
		datastore.setFullData(fullData);
		
		Optional<IDhApiDatastore.FullData> fetchedFullData = datastore.getFullData(levelId, (byte) 6, 4, 5);
		assert fetchedFullData.isPresent();
		assert fetchedFullData.get().levelId == fullData.levelId;
		assert fetchedFullData.get().detailLevel == fullData.detailLevel;
		assert fetchedFullData.get().posX == fullData.posX;
		assert fetchedFullData.get().posZ == fullData.posZ;
		assert fetchedFullData.get().minY == fullData.minY;
		assert fetchedFullData.get().dataChecksum == fullData.dataChecksum;
		assert Arrays.equals(fetchedFullData.get().data, fullData.data);
		assert Arrays.equals(fetchedFullData.get().columnGenerationStep, fullData.columnGenerationStep);
		assert Arrays.equals(fetchedFullData.get().columnWorldCompressionMode, fullData.columnWorldCompressionMode);
		assert Arrays.equals(fetchedFullData.get().mapping, fullData.mapping);
		assert fetchedFullData.get().dataFormatVersion == fullData.dataFormatVersion;
		assert fetchedFullData.get().compressionMode == fullData.compressionMode;
		assert fetchedFullData.get().applyToParent == fullData.applyToParent;
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
		fullData.compressionMode = 2;
		fullData.applyToParent = false;
		datastore.setFullData(fullData);
		
		Optional<IDhApiDatastore.FullDataMetadata> fetchedFullDataMetadata = datastore.getFullDataMetadata(levelId, (byte) 12, 5, 6);
		assert fetchedFullDataMetadata.isPresent();
		assert fetchedFullDataMetadata.get().levelId == fullData.levelId;
		assert fetchedFullDataMetadata.get().detailLevel == fullData.detailLevel;
		assert fetchedFullDataMetadata.get().posX == fullData.posX;
		assert fetchedFullDataMetadata.get().posZ == fullData.posZ;
		assert fetchedFullDataMetadata.get().minY == fullData.minY;
		assert fetchedFullDataMetadata.get().dataChecksum == fullData.dataChecksum;
		assert Arrays.equals(fetchedFullDataMetadata.get().columnGenerationStep, fullData.columnGenerationStep);
		assert Arrays.equals(fetchedFullDataMetadata.get().columnWorldCompressionMode, fullData.columnWorldCompressionMode);
		assert fetchedFullDataMetadata.get().dataFormatVersion == fullData.dataFormatVersion;
		assert fetchedFullDataMetadata.get().compressionMode == fullData.compressionMode;
		assert fetchedFullDataMetadata.get().applyToParent == fullData.applyToParent;
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
		fullData.compressionMode = 2;
		fullData.applyToParent = false;
		datastore.setFullData(fullData);
		
		Optional<IDhApiDatastore.FullDataMetadata> fetchedFullDataMetadata = datastore.getFullDataMetadata(levelId, (byte) 12, -45, 6);
		assert fetchedFullDataMetadata.isPresent();
		assert datastore.deleteFullData(levelId, (byte) 12, -45, 6);
		fetchedFullDataMetadata = datastore.getFullDataMetadata(levelId, (byte) 12, -45, 6);
		assert !fetchedFullDataMetadata.isPresent();
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
		fullData.compressionMode = 2;
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
		fullData.compressionMode = 2;
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
		fullData.compressionMode = 2;
		fullData.applyToParent = true;
		datastore.setFullData(fullData);
		
		List<IDhApiDatastore.FullDataPosition> fullDataPositions = datastore.getFullDataPositionsToUpdate(levelId, 0, 2, 3);
		assert fullDataPositions.stream().anyMatch(pos ->
				pos.detailLevel == 11 &&
						pos.posX == 576000 &&
						pos.posZ == 4
		);
		
		assert fullDataPositions.stream().anyMatch(pos ->
				pos.detailLevel == 11 &&
						pos.posX == 576000 &&
						pos.posZ == 4
		);
		
		assert fullDataPositions.stream().noneMatch(pos ->
				pos.detailLevel == 12 &&
						pos.posX == 576000 &&
						pos.posZ == 2
		);
	}
	
}
