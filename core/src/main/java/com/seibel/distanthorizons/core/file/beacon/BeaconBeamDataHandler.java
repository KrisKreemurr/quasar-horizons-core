package com.seibel.distanthorizons.core.file.beacon;

import com.seibel.distanthorizons.api.interfaces.override.datastore.IDhApiDatastore;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.render.renderer.generic.BeaconRenderHandler;
import com.seibel.distanthorizons.core.render.renderer.generic.GenericObjectRenderer;
import com.seibel.distanthorizons.core.util.KeyedLockContainer;
import com.seibel.distanthorizons.core.util.LodUtil;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class BeaconBeamDataHandler
{
	private final int levelId;
	private final IDhApiDatastore datastore;
	
	@Nullable
	private BeaconRenderHandler beaconRenderHandler;
	
	private final KeyedLockContainer<Long> updateLockContainer = new KeyedLockContainer<>();
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public BeaconBeamDataHandler(int levelId, @NotNull IDhApiDatastore datastore, @Nullable GenericObjectRenderer renderer)
	{
		this.levelId = levelId;
		this.datastore = datastore;
		
		if (renderer != null)
		{
			this.beaconRenderHandler = new BeaconRenderHandler(renderer);
		}
	}
	
	
	
	//==========//
	// updating //
	//==========//
	
	public void setBeaconBeamsForChunk(DhChunkPos chunkPos, List<IDhApiDatastore.BeaconBeam> activeBeamList)
	{
		long sectionPos = DhSectionPos.encode(LodUtil.CHUNK_DETAIL_LEVEL, chunkPos.getX(), chunkPos.getZ());
		this.setBeaconBeamsForPos(sectionPos, activeBeamList);
	}
	
	public void setBeaconBeamsForPos(long sectionPos, List<IDhApiDatastore.BeaconBeam> activeBeamList)
	{
		// locked to prevent two threads from updating the same section at the same time
		ReentrantLock lock = this.updateLockContainer.getLockForPos(sectionPos);
		try
		{
			lock.lock();
			
			HashSet<DhBlockPos> allPosSet = new HashSet<>();
			
			// sort new beams
			HashMap<DhBlockPos, IDhApiDatastore.BeaconBeam> activeBeamByPos = new HashMap<>(activeBeamList.size());
			for (IDhApiDatastore.BeaconBeam beam : activeBeamList)
			{
				activeBeamByPos.put(new DhBlockPos(beam.blockPosX, beam.blockPosY, beam.blockPosZ), beam);
				allPosSet.add(new DhBlockPos(beam.blockPosX, beam.blockPosY, beam.blockPosZ));
			}
			
			// get existing beams
			List<IDhApiDatastore.BeaconBeam> existingBeamList = this.datastore.listBeaconBeamsInArea(
					levelId,
					DhSectionPos.getMinCornerBlockX(sectionPos),
					DhSectionPos.getMinCornerBlockZ(sectionPos),
					DhSectionPos.getMinCornerBlockX(sectionPos) + DhSectionPos.getBlockWidth(sectionPos),
					DhSectionPos.getMinCornerBlockZ(sectionPos) + DhSectionPos.getBlockWidth(sectionPos)
			);
			HashMap<DhBlockPos, IDhApiDatastore.BeaconBeam> existingBeamByPos = new HashMap<>(existingBeamList.size());
			for (IDhApiDatastore.BeaconBeam beam : existingBeamList)
			{
				existingBeamByPos.put(new DhBlockPos(beam.blockPosX, beam.blockPosY, beam.blockPosZ), beam);
				allPosSet.add(new DhBlockPos(beam.blockPosX, beam.blockPosY, beam.blockPosZ));
			}
			
			
			
			for (DhBlockPos beaconPos : allPosSet)
			{
				if (!DhSectionPos.contains(sectionPos, beaconPos))
				{
					// don't update beacons outside the updated chunk
					continue;
				}
				
				IDhApiDatastore.BeaconBeam existingBeam = existingBeamByPos.get(beaconPos);
				IDhApiDatastore.BeaconBeam activeBeam = activeBeamByPos.get(beaconPos);
				
				
				if (activeBeam != null)
				{
					if (existingBeam == null)
					{
						// new beam found, add to DB
						this.datastore.setBeaconBeam(levelId, 
								activeBeam.blockPosX, 
								activeBeam.blockPosY, 
								activeBeam.blockPosZ, 
								activeBeam.colourR,
								activeBeam.colourG,
								activeBeam.colourB
						);
						if (this.beaconRenderHandler != null)
						{
							this.beaconRenderHandler.startRenderingBeacon(activeBeam);
						}
					}
					else
					{
						// beam still exists in chunk
						if (
								existingBeam.colourR != activeBeam.colourR || 
								existingBeam.colourG != activeBeam.colourG || 
								existingBeam.colourB != activeBeam.colourB
						)
						{
							// beam colors were changed
							this.datastore.setBeaconBeam(levelId,
									activeBeam.blockPosX,
									activeBeam.blockPosY,
									activeBeam.blockPosZ,
									activeBeam.colourR,
									activeBeam.colourG,
									activeBeam.colourB
							);
							if (this.beaconRenderHandler != null)
							{
								this.beaconRenderHandler.updateBeaconColor(activeBeam);
							}
						}
					}
				}
				else if (existingBeam != null)
				{
					// beam no longer exists at position, remove from DB
					this.datastore.deleteBeaconBeam(levelId, beaconPos.getX(), beaconPos.getY(), beaconPos.getZ());
					if (this.beaconRenderHandler != null)
					{
						this.beaconRenderHandler.stopRenderingBeaconAtPos(beaconPos);
					}
				}
				
			}
		}
		finally
		{
			lock.unlock();
		}
	}
	
	
	
	//===================//
	// loading/unloading //
	//===================//
	
	public void loadBeaconBeamsInPos(long pos)
	{
		if (this.beaconRenderHandler == null)
		{
			return;
		}
		
		// get all beams in pos
		List<IDhApiDatastore.BeaconBeam> existingBeamList = this.datastore.listBeaconBeamsInArea(
				levelId,
				DhSectionPos.getMinCornerBlockX(pos),
				DhSectionPos.getMinCornerBlockZ(pos),
				DhSectionPos.getMinCornerBlockX(pos) + DhSectionPos.getBlockWidth(pos),
				DhSectionPos.getMinCornerBlockZ(pos) + DhSectionPos.getBlockWidth(pos)
		);
		for (IDhApiDatastore.BeaconBeam newBeam : existingBeamList)
		{
			this.beaconRenderHandler.startRenderingBeacon(newBeam);
		}
	}
	
	public void unloadBeaconBeamsInPos(long pos)
	{
		if (this.beaconRenderHandler == null)
		{
			return;
		}
		
		// get all beams in pos
		List<IDhApiDatastore.BeaconBeam> existingBeamList = this.datastore.listBeaconBeamsInArea(
				levelId,
				DhSectionPos.getMinCornerBlockX(pos),
				DhSectionPos.getMinCornerBlockZ(pos),
				DhSectionPos.getMinCornerBlockX(pos) + DhSectionPos.getBlockWidth(pos),
				DhSectionPos.getMinCornerBlockZ(pos) + DhSectionPos.getBlockWidth(pos)
		);
		for (IDhApiDatastore.BeaconBeam beam : existingBeamList)
		{
			this.beaconRenderHandler.stopRenderingBeaconAtPos(new DhBlockPos(beam.blockPosX, beam.blockPosY, beam.blockPosZ));
		}
	}
	
}
