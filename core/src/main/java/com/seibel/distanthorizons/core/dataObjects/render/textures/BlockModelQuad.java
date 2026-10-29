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
import com.seibel.distanthorizons.core.util.math.DhVec3f;
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
	/** the quad's facing, octahedral encoded (x low byte, y high byte) */
	public final short packedNormal;
	/** the side the quad faces the most, used for vanilla's face shading */
	public final EDhDirection shadeDirection;
	
	
	
	private static DhVec3f getNormal(float[] cornerPositions)
	{
		// the diagonals' cross product works for any 4 corners
		DhVec3f normal = new DhVec3f(cornerPositions[6] - cornerPositions[0], cornerPositions[7] - cornerPositions[1], cornerPositions[8] - cornerPositions[2]);
		normal.crossProduct(new DhVec3f(cornerPositions[9] - cornerPositions[3], cornerPositions[10] - cornerPositions[4], cornerPositions[11] - cornerPositions[5]));
		return normal;
	}
	
	/** ties go to up/down, then north/south, like vanilla */
	private static EDhDirection getShadeDirection(DhVec3f normal)
	{
		float absX = Math.abs(normal.x);
		float absY = Math.abs(normal.y);
		float absZ = Math.abs(normal.z);
		if (absY >= absX && absY >= absZ)
		{
			return (normal.y >= 0) ? EDhDirection.UP : EDhDirection.DOWN;
		}
		else if (absZ >= absX)
		{
			return (normal.z >= 0) ? EDhDirection.SOUTH : EDhDirection.NORTH;
		}
		return (normal.x >= 0) ? EDhDirection.EAST : EDhDirection.WEST;
	}
	
	private static short getPackedNormal(DhVec3f normal)
	{
		normal.mul(1 / (Math.abs(normal.x) + Math.abs(normal.y) + Math.abs(normal.z)));
		
		float octX = normal.x;
		float octY = normal.y;
		if (normal.z < 0)
		{
			octX = (1 - Math.abs(normal.y)) * (normal.x >= 0 ? 1 : -1);
			octY = (1 - Math.abs(normal.x)) * (normal.y >= 0 ? 1 : -1);
		}
		
		int encodedX = Math.round((octX * 0.5f + 0.5f) * 255);
		int encodedY = Math.round((octY * 0.5f + 0.5f) * 255);
		return (short) (encodedX | (encodedY << 8));
	}
	
	public BlockModelQuad(float[] cornerPositions, float[] cornerUvs, BlockFaceTexture texture, @Nullable EDhDirection cullDirection)
	{
		this.cornerPositions = cornerPositions;
		this.cornerUvs = cornerUvs;
		this.texture = texture;
		this.cullDirection = cullDirection;
		
		DhVec3f normal = getNormal(cornerPositions);
		this.shadeDirection = getShadeDirection(normal);
		this.packedNormal = getPackedNormal(normal);
	}
	
}
