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

package com.seibel.distanthorizons.api.objects.render;

import com.seibel.distanthorizons.api.enums.rendering.EDhApiDirection;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBlockModelOverrideEvent;

/**
 * One quad of a block's model, see {@link DhApiBlockModelOverrideEvent}. <br><br>
 *
 * Corners go counter-clockwise when looking at the quad's visible side,
 * the same order Minecraft's baked quads use.
 *
 * @author Luis Vervaet
 * @version 2026-10-29
 * @since API 7.3.0
 */
public class DhApiModelQuad
{
	/** in blocks, relative to the block's minimum corner */
	public final float[] xByCorner;
	public final float[] yByCorner;
	public final float[] zByCorner;
	/** 0 to 1 across the sprite, (0,0) is the top left */
	public final float[] uByCorner;
	public final float[] vByCorner;
	/** Minecraft's TextureAtlasSprite from the block atlas */
	public final Object sprite;
	/** the side whose neighbor can hide this quad, null if no neighbor can */
	public final EDhApiDirection cullDirection;
	
	
	
	public DhApiModelQuad(
		float[] xByCorner, float[] yByCorner, float[] zByCorner,
		float[] uByCorner, float[] vByCorner,
		Object sprite, EDhApiDirection cullDirection) throws IllegalArgumentException
	{
		throwIfNotFourCorners("xByCorner", xByCorner);
		throwIfNotFourCorners("yByCorner", yByCorner);
		throwIfNotFourCorners("zByCorner", zByCorner);
		throwIfNotFourCorners("uByCorner", uByCorner);
		throwIfNotFourCorners("vByCorner", vByCorner);
		
		this.xByCorner = xByCorner;
		this.yByCorner = yByCorner;
		this.zByCorner = zByCorner;
		this.uByCorner = uByCorner;
		this.vByCorner = vByCorner;
		this.sprite = sprite;
		this.cullDirection = cullDirection;
	}
	
	private static void throwIfNotFourCorners(String name, float[] values) throws IllegalArgumentException
	{
		if (values == null || values.length != 4)
		{
			throw new IllegalArgumentException("["+name+"] must have 4 values, one per corner.");
		}
	}
	
}
