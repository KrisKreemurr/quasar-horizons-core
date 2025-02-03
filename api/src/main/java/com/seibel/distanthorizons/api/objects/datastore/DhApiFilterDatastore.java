package com.seibel.distanthorizons.api.objects.datastore;

import com.seibel.distanthorizons.api.interfaces.override.datastore.IDhApiDatastore;

import java.util.List;
import java.util.Optional;

public class DhApiFilterDatastore implements IDhApiDatastore {
	IDhApiDatastore datastore;
	public DhApiFilterDatastore(IDhApiDatastore datastore) {
		this.datastore = datastore;
	}

	@Override
	public int ensureLevel(String uniqueLevelName) {
		return datastore.ensureLevel(uniqueLevelName);
	}

	@Override
	public void setChunkHash(int levelId, int chunkPosX, int chunkPosZ, int chunkHash) {
		datastore.setChunkHash(levelId, chunkPosX, chunkPosZ, chunkHash);
	}

	@Override
	public Optional<Integer> getChunkHash(int levelId, int chunkPosX, int chunkPosZ) {
		return datastore.getChunkHash(levelId, chunkPosX, chunkPosZ);
	}

	@Override
	public void setBeaconBeam(int levelId, int blockPosX, int blockPosY, int blockPosZ, int colourR, int colourG, int colourB) {
		datastore.setBeaconBeam(levelId, blockPosX, blockPosY,blockPosZ, colourR, colourG, colourB);
	}

	@Override
	public boolean deleteBeaconBeam(int levelId, int blockPosX, int blockPosY, int blockPosZ) {
		return datastore.deleteBeaconBeam(levelId, blockPosX, blockPosY, blockPosZ);
	}

	@Override
	public List<BeaconBeam> listBeaconBeamsInArea(int levelId, int minBlockPosX, int minBlockPosZ, int maxBlockPosX, int maxBlockPosZ) {
		return datastore.listBeaconBeamsInArea(levelId, minBlockPosX, minBlockPosZ, maxBlockPosX, maxBlockPosZ);
	}

	@Override
	public void setFullData(FullData fullData) {
		datastore.setFullData(fullData);
	}

	@Override
	public Optional<FullData> getFullData(int levelId, byte detailLevel, int posX, int posZ) {
		return datastore.getFullData(levelId, detailLevel, posX, posZ);
	}

	@Override
	public Optional<FullDataMetadata> getFullDataMetadata(int levelId, byte detailLevel, int posX, int posZ) {
		return datastore.getFullDataMetadata(levelId, detailLevel, posX, posZ);
	}

	@Override
	public boolean updateFullDataApplyToParent(int levelId, byte detailLevel, int posX, int posZ, boolean applyToParent) {
		return datastore.updateFullDataApplyToParent(levelId, detailLevel, posX, posZ, applyToParent);
	}

	@Override
	public boolean deleteFullData(int levelId, byte detailLevel, int posX, int posZ) {
		return datastore.deleteFullData(levelId, detailLevel, posX, posZ);
	}

	@Override
	public List<FullDataPosition> getFullDataPositionsToUpdate(int levelId, int posX, int posZ, int returnCount) {
		return datastore.getFullDataPositionsToUpdate(levelId, posX, posZ, returnCount);
	}
}
