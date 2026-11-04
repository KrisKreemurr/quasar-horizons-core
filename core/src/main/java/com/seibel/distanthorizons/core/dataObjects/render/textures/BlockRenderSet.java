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
	public final BlockModelQuad[] modelQuads;
	/** indexed the same as modelQuads */
	public final short[] modelQuadTileIds;
	/** each quad's own color or {@link #USE_LOD_COLOR}, indexed the same as modelQuads */
	public final int[] modelQuadColors;
	/** the first block state registered with this set, null for the shared flat set */
	@Nullable
	public final IBlockStateWrapper blockState;
	
	
	
	public BlockRenderSet() 
	{
		// 6 faces on a cube
		this(new short[6], new int[6], new BlockModelQuad[0], new short[0], new int[0], null);
	}
	public BlockRenderSet(short[] faceTileIds, int[] faceColors, BlockModelQuad[] modelQuads, short[] modelQuadTileIds, int[] modelQuadColors, @Nullable IBlockStateWrapper blockState)
	{
		this.faceTileIds = faceTileIds;
		this.faceColors = faceColors;
		this.modelQuads = modelQuads;
		this.modelQuadTileIds = modelQuadTileIds;
		this.modelQuadColors = modelQuadColors;
		this.blockState = blockState;
	}
	
}
