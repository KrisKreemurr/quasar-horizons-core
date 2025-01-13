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
import com.seibel.distanthorizons.core.file.beacon.BeaconBeamDataHandler;
import com.seibel.distanthorizons.core.file.fullDatafile.DelayedFullDataSourceSaveCache;
import com.seibel.distanthorizons.core.generation.DhLightingEngine;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.renderer.generic.CloudRenderHandler;
import com.seibel.distanthorizons.core.render.renderer.generic.GenericObjectRenderer;
import com.seibel.distanthorizons.core.sql.SQLiteDatastore;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public abstract class AbstractDhLevel implements IDhLevel
{
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	
	public IDhApiDatastore datastore;
	public int levelId;
	
	protected final DelayedFullDataSourceSaveCache delayedFullDataSourceSaveCache = new DelayedFullDataSourceSaveCache(this::onDataSourceSaveAsync, 3_000);
	/** contains the {@link DhChunkPos} for each {@link DhSectionPos} that are queued to save */
	protected final ConcurrentHashMap<Long, HashSet<DhChunkPos>> updatedChunkPosSetBySectionPos = new ConcurrentHashMap<>();
	protected final ConcurrentHashMap<DhChunkPos, Integer> updatedChunkHashesByChunkPos = new ConcurrentHashMap<>();
	
	/** Will be null if clouds shouldn't be rendered for this level. */
	@Nullable
	protected CloudRenderHandler cloudRenderHandler;
	protected BeaconBeamDataHandler beaconBeamDataHandler;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	protected AbstractDhLevel() {  }
	
	/** handles any setup that needs the repos to be created */
	protected void runRepoReliantSetup()
	{
		GenericObjectRenderer genericRenderer = this.getGenericRenderer();
		if (genericRenderer != null)
		{
			// only client levels can render clouds
			if (this instanceof IDhClientLevel)
			{
				// only add clouds for certain dimension types
				if (!this.getLevelWrapper().hasCeiling()
						&& !this.getLevelWrapper().getDimensionType().isTheEnd())
				{
					this.cloudRenderHandler = new CloudRenderHandler((IDhClientLevel)this, genericRenderer);
				}
			}
		}
		
		
		// shouldn't happen, but just in case
		if (this.datastore != null)
		{
			this.beaconBeamDataHandler = new BeaconBeamDataHandler(this.levelId, this.datastore, genericRenderer);
		}
	}
	
	
	
	//=================//
	// default methods //
	//=================//
	
	@Override
	public void updateChunkAsync(IChunkWrapper chunkWrapper, int chunkHash)
	{
		try (FullDataSourceV2 dataSource = FullDataSourceV2.createFromChunk(chunkWrapper))
		{
			if (dataSource == null)
			{
				// This can happen if, among other reasons, a chunk save is superseded by a later event
				return;
			}
			
			
			this.updatedChunkPosSetBySectionPos.compute(dataSource.getPos(), (dataSourcePos, chunkPosSet) ->
			{
				if (chunkPosSet == null)
				{
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
	
	private CompletableFuture<Void> onDataSourceSaveAsync(FullDataSourceV2 fullDataSource)
	{
		// block lights should have been populated at the chunkWrapper stage
		// waiting to populate the data source's skylight at this stage prevents re-lighting and
		// allows us to reduce cross-chunk lighting issues by lighting the whole 4x4 LOD at once 
		DhLightingEngine.INSTANCE.bakeDataSourceSkyLight(fullDataSource, this.hasSkyLight() ? LodUtil.MAX_MC_LIGHT : LodUtil.MIN_MC_LIGHT);
		
		
		return this.updateDataSourcesAsync(fullDataSource)
			.thenRun(() -> 
			{
				HashSet<DhChunkPos> updatedChunkPosSet = this.updatedChunkPosSetBySectionPos.remove(fullDataSource.getPos());
				if (updatedChunkPosSet != null)
				{
					for (DhChunkPos chunkPos : updatedChunkPosSet)
					{
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
	public int getChunkHash(DhChunkPos pos)
	{
		if (this.datastore == null)
			return 0;
		
		return this.datastore.getChunkHash(levelId, pos.getX(), pos.getZ());
	}
	
	
	
	//=================//
	// beacon handling //
	//=================//
	
	@Override
	public void updateBeaconBeamsForChunk(IChunkWrapper chunkToUpdate, ArrayList<IChunkWrapper> nearbyChunkList)
	{
		if (this.beaconBeamDataHandler != null)
		{
			List<IDhApiDatastore.BeaconBeam> activeBeamList = chunkToUpdate.getAllActiveBeacons(nearbyChunkList);
			this.beaconBeamDataHandler.setBeaconBeamsForChunk(chunkToUpdate.getChunkPos(), activeBeamList);
		}
	}
	
	@Override
	public void loadBeaconBeamsInPos(long pos)
	{
		if (this.beaconBeamDataHandler != null)
		{
			this.beaconBeamDataHandler.loadBeaconBeamsInPos(pos);
		}
	}
	@Override
	public void unloadBeaconBeamsInPos(long pos)
	{
		if (this.beaconBeamDataHandler != null)
		{
			this.beaconBeamDataHandler.unloadBeaconBeamsInPos(pos);
		}
	}
	
	
	
	//================//
	// base overrides //
	//================//
	
	@Override
	public void close() 
	{ 
		this.delayedFullDataSourceSaveCache.close();
	}
}
