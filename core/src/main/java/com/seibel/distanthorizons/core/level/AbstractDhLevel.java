/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.core.level;

import com.seibel.distanthorizons.api.interfaces.override.datastore.IDhApiDatastore;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiChunkModifiedEvent;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.file.fullDatafile.DelayedFullDataSourceSaveCache;
import com.seibel.distanthorizons.core.generation.DhLightingEngine;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.render.renderer.generic.CloudRenderHandler;
import com.seibel.distanthorizons.core.render.renderer.generic.GenericObjectRenderer;
import com.seibel.distanthorizons.core.util.KeyedLockContainer;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public abstract class AbstractDhLevel implements IDhLevel {
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();

	public IDhApiDatastore datastore;
	public int levelId;

	protected final KeyedLockContainer<Long> beaconUpdateLockContainer = new KeyedLockContainer<>();

	protected final DelayedFullDataSourceSaveCache delayedFullDataSourceSaveCache = new DelayedFullDataSourceSaveCache(this::onDataSourceSaveAsync, 3_000);
	/**
	 * contains the {@link DhChunkPos} for each {@link DhSectionPos} that are queued to save
	 */
	protected final ConcurrentHashMap<Long, HashSet<DhChunkPos>> updatedChunkPosSetBySectionPos = new ConcurrentHashMap<>();
	protected final ConcurrentHashMap<DhChunkPos, Integer> updatedChunkHashesByChunkPos = new ConcurrentHashMap<>();

	/**
	 * Will be null if clouds shouldn't be rendered for this level.
	 */
	@Nullable
	protected CloudRenderHandler cloudRenderHandler;


	//=============//
	// constructor //
	//=============//

	protected AbstractDhLevel() {
	}

	/**
	 * handles any setup that needs the repos to be created
	 */
	protected void runRepoReliantSetup() {
		GenericObjectRenderer genericRenderer = this.getGenericRenderer();
		if (genericRenderer != null) {
			// only client levels can render clouds
			if (this instanceof IDhClientLevel) {
				// only add clouds for certain dimension types
				if (!this.getLevelWrapper().hasCeiling()
					&& !this.getLevelWrapper().getDimensionType().isTheEnd()) {
					this.cloudRenderHandler = new CloudRenderHandler((IDhClientLevel) this, genericRenderer);
				}
			}
		}
	}


	//=================//
	// default methods //
	//=================//

	@Override
	public void updateChunkAsync(IChunkWrapper chunkWrapper, int chunkHash) {
		try (FullDataSourceV2 dataSource = FullDataSourceV2.createFromChunk(chunkWrapper)) {
			if (dataSource == null) {
				// This can happen if, among other reasons, a chunk save is superseded by a later event
				return;
			}


			this.updatedChunkPosSetBySectionPos.compute(dataSource.getPos(), (dataSourcePos, chunkPosSet) ->
			{
				if (chunkPosSet == null) {
					chunkPosSet = new HashSet<>();
				}
				chunkPosSet.add(chunkWrapper.getChunkPos());
				return chunkPosSet;
			});
			this.updatedChunkHashesByChunkPos.put(chunkWrapper.getChunkPos(), chunkHash);

			// merging writes together in memory significantly improves throughput, since most
			// chunk modifications will be right next to each other, IE effecting the same LODs
			this.delayedFullDataSourceSaveCache.writeDataSourceToMemoryAndQueueSave(dataSource);
		}
	}

	private CompletableFuture<Void> onDataSourceSaveAsync(FullDataSourceV2 fullDataSource) {
		// block lights should have been populated at the chunkWrapper stage
		// waiting to populate the data source's skylight at this stage prevents re-lighting and
		// allows us to reduce cross-chunk lighting issues by lighting the whole 4x4 LOD at once
		DhLightingEngine.INSTANCE.bakeDataSourceSkyLight(fullDataSource, this.hasSkyLight() ? LodUtil.MAX_MC_LIGHT : LodUtil.MIN_MC_LIGHT);


		return this.updateDataSourcesAsync(fullDataSource)
				.thenRun(() ->
				{
					HashSet<DhChunkPos> updatedChunkPosSet = this.updatedChunkPosSetBySectionPos.remove(fullDataSource.getPos());
					if (updatedChunkPosSet != null) {
						for (DhChunkPos chunkPos : updatedChunkPosSet) {
							// save after the data source has been updated to prevent saving the hash without the associated datasource
							Integer chunkHash = this.updatedChunkHashesByChunkPos.remove(chunkPos);
							if (this.datastore != null)
								this.datastore.setChunkHash(levelId, chunkPos.getX(), chunkPos.getZ(), chunkHash);

							ApiEventInjector.INSTANCE.fireAllEvents(
									DhApiChunkModifiedEvent.class,
									new DhApiChunkModifiedEvent.EventParam(this.getLevelWrapper(), chunkPos.getX(), chunkPos.getZ()));
						}
					}
				});
	}


	//=======//
	// repos //
	//=======//

	// chunk hash //

	@Override
	public int getChunkHash(DhChunkPos pos) {
		if (this.datastore == null)
			return 0;

		return this.datastore.getChunkHash(levelId, pos.getX(), pos.getZ()).orElse(0);
	}


	//=================//
	// beacon handling //
	//=================//

	@Override
	public void updateBeaconBeamsForSectionPos(long sectionPos, List<IDhApiDatastore.BeaconBeam> activeBeamList) {
		int minBlockX = DhSectionPos.getMinCornerBlockX(sectionPos);
		int minBlockZ = DhSectionPos.getMinCornerBlockZ(sectionPos);
		// TODO special logic had to be done for DhChunkPos.getMaxBlock,
		//  does that need to be done here?
		//  The DhChunkPos issue caused beacons to appear/disappear incorrectly on negative chunk borders
		int maxBlockX = minBlockX + DhSectionPos.getBlockWidth(sectionPos);
		int maxBlockZ = minBlockZ + DhSectionPos.getBlockWidth(sectionPos);

		this.updateBeaconBeamsBetweenBlockPos(
				sectionPos,
				minBlockX, maxBlockX,
				minBlockZ, maxBlockZ,
				activeBeamList
		);
	}

	@Override
	public void updateBeaconBeamsForChunkPos(DhChunkPos chunkPos, List<IDhApiDatastore.BeaconBeam> activeBeamList) {
		long sectionPos = DhSectionPos.encodeContaining(DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL, chunkPos);

		int minBlockX = chunkPos.getMinBlockX();
		int minBlockZ = chunkPos.getMinBlockZ();
		int maxBlockX = chunkPos.getMaxBlockX();
		int maxBlockZ = chunkPos.getMaxBlockZ();

		//LOGGER.info("beacons ["+activeBeamList.size()+"] at ["+chunkPos+"] x["+minBlockX+"]-["+maxBlockX+"] z["+minBlockZ+"]-["+maxBlockZ+"].");

		this.updateBeaconBeamsBetweenBlockPos(
				sectionPos,
				minBlockX, maxBlockX,
				minBlockZ, maxBlockZ,
				activeBeamList
		);
	}

	private void updateBeaconBeamsBetweenBlockPos(
			long sectionPosForLock,
			int minBlockX, int maxBlockX,
			int minBlockZ, int maxBlockZ,
			List<IDhApiDatastore.BeaconBeam> activeBeamList
	) // TODO min/max block pos instead
	{
		// locked to prevent two threads from updating the same section at the same time
		ReentrantLock lock = this.beaconUpdateLockContainer.getLockForPos(sectionPosForLock);
		try {
			lock.lock();

			HashSet<DhBlockPos> allPosSet = new HashSet<>();

			// sort new beams
			HashMap<DhBlockPos, IDhApiDatastore.BeaconBeam> activeBeamByPos = new HashMap<>(activeBeamList.size());
			for (IDhApiDatastore.BeaconBeam beam : activeBeamList) {
				DhBlockPos pos = new DhBlockPos(beam.blockPosX, beam.blockPosY, beam.blockPosZ);
				activeBeamByPos.put(pos, beam);
				allPosSet.add(pos);
			}

			// get existing beams
			List<IDhApiDatastore.BeaconBeam> existingBeamList = this.datastore.listBeaconBeamsInArea(
					levelId,
					minBlockX, maxBlockX,
					minBlockZ, maxBlockZ);
			HashMap<DhBlockPos, IDhApiDatastore.BeaconBeam> existingBeamByPos = new HashMap<>(existingBeamList.size());
			for (IDhApiDatastore.BeaconBeam beam : existingBeamList) {
				DhBlockPos pos = new DhBlockPos(beam.blockPosX, beam.blockPosY, beam.blockPosZ);
				existingBeamByPos.put(pos, beam);
				allPosSet.add(pos);
			}


			for (DhBlockPos beaconPos : allPosSet) {
				if (minBlockX <= beaconPos.getX() && beaconPos.getX() <= maxBlockX
					&& minBlockZ <= beaconPos.getZ() && beaconPos.getZ() <= maxBlockZ) {
					//// don't modify beacons outside the updated range
					//continue;
				} else {
					continue;
				}


				IDhApiDatastore.BeaconBeam existingBeam = existingBeamByPos.get(beaconPos);
				IDhApiDatastore.BeaconBeam activeBeam = activeBeamByPos.get(beaconPos);
				if (activeBeam != null) {
					//LOGGER.info("add beacon ["+activeBeam.blockPos+"] x["+minBlockX+"]-["+maxBlockX+"] z["+minBlockZ+"]-["+maxBlockZ+"].");

					if (
							existingBeam == null ||
							existingBeam.colourR != activeBeam.colourR ||
							existingBeam.colourG != activeBeam.colourG ||
							existingBeam.colourB != activeBeam.colourB
					) {
						// new beam found, add to DB
						this.datastore.setBeaconBeam(
								levelId,
								activeBeam.blockPosX,
								activeBeam.blockPosY,
								activeBeam.blockPosZ,
								activeBeam.colourR,
								activeBeam.colourG,
								activeBeam.colourB
						);
					}
				} else if (existingBeam != null) {
					// beam no longer exists at position, remove from DB
					this.datastore.deleteBeaconBeam(levelId, existingBeam.blockPosX, existingBeam.blockPosY, existingBeam.blockPosZ);
					//LOGGER.info("remove beacon ["+beaconPos+"] x["+minBlockX+"]-["+maxBlockX+"] z["+minBlockZ+"]-["+maxBlockZ+"].");
				}
			}
		} finally {
			lock.unlock();
		}
	}

	//================//
	// base overrides //
	//================//

	@Override
	public void close() {
		this.delayedFullDataSourceSaveCache.close();
	}
}
