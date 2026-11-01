package com.seibel.distanthorizons.core.api.internal.chunkUpdating;

import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.coreapi.util.BitShiftUtil;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;

public class ChunkPosQueue
{
	private final PriorityBlockingQueue<DhChunkPos> closestQueue;
	private final PriorityBlockingQueue<DhChunkPos> furthestQueue;
	private final ConcurrentHashMap<DhChunkPos, ChunkUpdateData> updateDataByChunkPos;
	
	// defaulting to 0,0 is fine since it'll be updated once we start adding items
	private volatile long centerPacked = BitShiftUtil.packInts(0, 0);
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public ChunkPosQueue()
	{
		this.closestQueue = new PriorityBlockingQueue<>(500, Comparator.comparingLong(pos -> pos.squaredDistance(this.centerPacked)));
		this.furthestQueue = new PriorityBlockingQueue<>(500, Comparator.comparingLong(pos -> ((DhChunkPos)pos).squaredDistance(this.centerPacked)).reversed());
		this.updateDataByChunkPos = new ConcurrentHashMap<>();
	}
	
	
	
	//==============//
	// list methods //
	//==============//
	
	public boolean contains(DhChunkPos pos) { return this.updateDataByChunkPos.containsKey(pos); }
	
	public void clear()
	{
		this.updateDataByChunkPos.clear();
		this.closestQueue.clear();
		this.furthestQueue.clear();
	}
	
	public void addItem(DhChunkPos pos, ChunkUpdateData updateData)
	{
		if (this.updateDataByChunkPos.containsKey(pos))
		{
			// Chunk is already present in queue, no need to insert
			return;
		}
		this.updateDataByChunkPos.put(pos, updateData);
		this.closestQueue.add(pos);
		this.furthestQueue.add(pos);
	}
	
	public int getQueuedCount() { return this.updateDataByChunkPos.size(); }
	
	public boolean isEmpty() { return this.updateDataByChunkPos.isEmpty(); }
	
	
	
	//==================//
	// position methods //
	//==================//
	
	public void setCenter(DhChunkPos newCenter)
	{
		this.setCenterPacked(BitShiftUtil.packInts(newCenter.getX(), newCenter.getZ()));
	}
	
	public void setCenterPacked(long newCenterPacked)
	{
		// if the rebuild time takes too long 
		// (in James' testing a queue of 500 items only took around 0.1 milliseconds)
		// this equation could be changed to only update after moving 2 or 4 chunks from the center
		if (newCenterPacked == this.centerPacked)
		{
			return;
		}
		
		this.centerPacked = newCenterPacked;
		
		// rebuild the priority queues to match the new center
		this.rebuildPriorityQueues();
	}
	
	private void rebuildPriorityQueues()
	{
		this.closestQueue.clear();
		this.furthestQueue.clear();
		for (DhChunkPos pos : this.updateDataByChunkPos.keySet())
		{
			this.closestQueue.add(pos);
			this.furthestQueue.add(pos);
		}
	}
	
	public ChunkUpdateData popClosest()
	{
		if (this.closestQueue.isEmpty())
		{
			return null;
		}
		
		DhChunkPos closest = this.closestQueue.poll();
		if (closest == null)
		{
			return null;
		}
		
		this.furthestQueue.remove(closest);
		return this.updateDataByChunkPos.remove(closest);
	}
	
	@Nullable
	public ChunkUpdateData popFurthest()
	{
		if (this.furthestQueue.isEmpty())
		{
			return null;
		}
		
		DhChunkPos furthest = this.furthestQueue.poll();
		if (furthest == null)
		{
			return null;
		}
		
		this.closestQueue.remove(furthest);
		return this.updateDataByChunkPos.remove(furthest);
	}
	
	@Nullable
	public ChunkUpdateData peekClosest()
	{
		DhChunkPos closest = this.closestQueue.peek();
		if (closest == null)
		{
			return null;
		}
		return this.updateDataByChunkPos.get(closest);
	}
}
		
