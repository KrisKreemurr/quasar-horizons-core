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
import org.jetbrains.annotations.Nullable;

/** One quad of a block's model */
public class BlockModelQuad
{
	/** x, y, z of each of the 4 corners, in the range [0,1] inside the block */
	public final float[] cornerPositions;
	/** u, v of each of the 4 corners, in the range [0,1] inside the texture */
	public final float[] cornerUvs;
	public final BlockFaceTexture texture;
	/** the side whose neighbor can hide this quad, null if it's always drawn (fence post insides) */
	@Nullable
	public final EDhDirection cullDirection;
	
	
	
	public BlockModelQuad(float[] cornerPositions, float[] cornerUvs, BlockFaceTexture texture, @Nullable EDhDirection cullDirection)
	{
		this.cornerPositions = cornerPositions;
		this.cornerUvs = cornerUvs;
		this.texture = texture;
		this.cullDirection = cullDirection;
	}
	
}
