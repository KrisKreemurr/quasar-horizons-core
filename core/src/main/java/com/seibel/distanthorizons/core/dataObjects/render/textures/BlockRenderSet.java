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

package com.seibel.distanthorizons.core.dataObjects.render.textures;

import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;

/** The face tiles, colors and model shared by block states that look the same, see {@link BlockRenderRegistry} */
public class BlockRenderSet
{
	/** a face color that means the face uses the LOD's color instead (biome tinted faces) */
	public static final int USE_LOD_COLOR = 0;
	
	/** indexed by {@link EDhDirection#faceIndex}, fluids also have their turned flow tiles after the 6 faces */
	public final short[] faceTileIds;
	/** each face's own color or {@link #USE_LOD_COLOR}, indexed by {@link EDhDirection#faceIndex} */
	public final int[] faceColors;
	/** empty if the block state isn't drawn with its model */
	public final DhBlockModelQuad[] modelQuads;
	/** indexed the same as modelQuads */
	public final short[] modelQuadTileIds;
	/** each quad's own color or {@link #USE_LOD_COLOR}, indexed the same as modelQuads */
	public final int[] modelQuadColors;
	/** the first block state registered with this set, null for the shared flat set */
	@Nullable
	public final IBlockStateWrapper blockState;
	/** which of this set's sides each neighbor set hides */
	private final ConcurrentHashMap<BlockRenderSet, boolean[]> hiddenSidesByNeighbor = new ConcurrentHashMap<>();
	
	
	
	public BlockRenderSet() 
	{
		// 6 faces on a cube
		this(new short[6], new int[6], new DhBlockModelQuad[0], new short[0], new int[0], null);
	}
	public BlockRenderSet(short[] faceTileIds, int[] faceColors, DhBlockModelQuad[] modelQuads, short[] modelQuadTileIds, int[] modelQuadColors, @Nullable IBlockStateWrapper blockState)
	{
		this.faceTileIds = faceTileIds;
		this.faceColors = faceColors;
		this.modelQuads = modelQuads;
		this.modelQuadTileIds = modelQuadTileIds;
		this.modelQuadColors = modelQuadColors;
		this.blockState = blockState;
	}
	
	/** True if every quad on that side is inside a solid quad on the neighbor's opposite side */
	private boolean calculateSideHiddenBy(BlockRenderSet neighbor, EDhDirection side)
	{
		boolean sideHasQuads = false;
		for (DhBlockModelQuad quad : this.modelQuads)
		{
			if (quad.cullDirection != side)
			{
				continue;
			}
			sideHasQuads = true;
			
			boolean quadHidden = false;
			for (DhBlockModelQuad neighborQuad : neighbor.modelQuads)
			{
				// the shader cuts out clear pixels
				if (neighborQuad.cullDirection == side.opposite()
					&& quad.isInsideNeighborQuad(neighborQuad, side)
					&& !neighborQuad.texture.hasClearPixels())
				{
					quadHidden = true;
					break;
				}
			}
			
			if (!quadHidden)
			{
				return false;
			}
		}
		
		// sides without quads keep their light
		return sideHasQuads;
	}
	
	private boolean[] calculateSidesHiddenBy(BlockRenderSet neighbor)
	{
		boolean[] hiddenSides = new boolean[EDhDirection.ALL.length];
		for (EDhDirection side : EDhDirection.ALL)
		{
			hiddenSides[side.faceIndex] = this.calculateSideHiddenBy(neighbor, side);
		}
		return hiddenSides;
	}
	
	/** True if the neighbor's quads hide every quad on that side */
	public boolean isSideHiddenBy(BlockRenderSet neighbor, EDhDirection side)
	{
		// worked out once per neighbor
		boolean[] hiddenSides = this.hiddenSidesByNeighbor.computeIfAbsent(neighbor, this::calculateSidesHiddenBy);
		return hiddenSides[side.faceIndex];
	}
	
}
