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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

/** The quads on one side of a model that a neighbor can hide */
public class SideShape
{
	/** shapes are shared by their content */
	private static final ConcurrentHashMap<BlockRenderRegistry.ContentKey, SideShape> SHAPE_BY_CONTENT = new ConcurrentHashMap<>();
	
	private final EDhDirection side;
	private final DhBlockModelQuad[] quads;
	/** neighbor shape on the opposite side -> true if it hides this shape */
	private final ConcurrentHashMap<SideShape, Boolean> hiddenByNeighborShape = new ConcurrentHashMap<>();
	
	
	
	private SideShape(EDhDirection side, DhBlockModelQuad[] quads)
	{
		this.side = side;
		this.quads = quads;
	}
	
	/** Null if no quads are on that side */
	@Nullable
	public static SideShape getSideShape(DhBlockModelQuad[] modelQuads, EDhDirection side)
	{
		ArrayList<DhBlockModelQuad> sideQuads = new ArrayList<>();
		for (DhBlockModelQuad quad : modelQuads)
		{
			if (quad.cullDirection == side)
			{
				sideQuads.add(quad);
			}
		}
		
		if (sideQuads.isEmpty())
		{
			return null;
		}
		
		// the side, then each quad's corners and if it's solid
		int byteCount = Byte.BYTES;
		for (DhBlockModelQuad quad : sideQuads)
		{
			byteCount += (quad.cornerPositions.length * Integer.BYTES) + Byte.BYTES;
		}
		ByteBuffer buffer = ByteBuffer.allocate(byteCount);
		buffer.put((byte) side.faceIndex);
		for (DhBlockModelQuad quad : sideQuads)
		{
			for (int position : quad.cornerPositions)
			{
				buffer.putInt(position);
			}
			buffer.put((byte) (quad.texture.hasClearPixels() ? 0 : 1));
		}
		
		BlockRenderRegistry.ContentKey contentKey = new BlockRenderRegistry.ContentKey(buffer.array());
		return SHAPE_BY_CONTENT.computeIfAbsent(contentKey, (newKey) -> new SideShape(side, sideQuads.toArray(new DhBlockModelQuad[0])));
	}
	
	/** True if every quad is inside a solid quad of the neighbor's shape */
	private boolean calculateHiddenBy(SideShape neighborShape)
	{
		for (DhBlockModelQuad quad : this.quads)
		{
			boolean quadHidden = false;
			for (DhBlockModelQuad neighborQuad : neighborShape.quads)
			{
				// the shader cuts out clear pixels
				if (quad.isInsideNeighborQuad(neighborQuad, this.side)
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
		return true;
	}
	
	/** True if the neighbor's shape on the opposite side hides every quad */
	public boolean isHiddenBy(SideShape neighborShape)
	{
		// worked out once per pair of shapes
		return this.hiddenByNeighborShape.computeIfAbsent(neighborShape, this::calculateHiddenBy);
	}
	
}
