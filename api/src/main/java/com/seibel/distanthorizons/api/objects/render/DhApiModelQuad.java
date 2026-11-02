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
import com.seibel.distanthorizons.coreapi.util.TextureUtil;

/**
 * One quad of a block's model, see {@link DhApiBlockModelOverrideEvent}. <br>
 * Each ByCorner array has 4 values, one per corner of this quad. <br><br>
 *
 * Corners go counter-clockwise when looking at the quad's visible side,
 * the same order Minecraft's baked quads use.
 *
 * @author Luis Vervaet
 * @version 2026-11-02
 * @since API 7.3.0
 */
public class DhApiModelQuad
{
	/** in blocks, relative to the block's minimum corner */
	public final float[] xByCorner;
	public final float[] yByCorner;
	public final float[] zByCorner;
	/** 0 to 1 across the texture, (0,0) is the top left */
	public final float[] uByCorner;
	public final float[] vByCorner;
	/** 16 by 16 ARGB pixels, indexed with {@link TextureUtil#getPixelIndex(int, int)} */
	public final int[] argbPixels;
	/** the side whose neighbor can hide this quad, null if no neighbor can */
	public final EDhApiDirection cullDirection;
	
	
	
	public DhApiModelQuad(
		float[] xByCorner, float[] yByCorner, float[] zByCorner,
		float[] uByCorner, float[] vByCorner,
		int[] argbPixels, EDhApiDirection cullDirection) throws IllegalArgumentException
	{
		throwIfNotFourCorners("xByCorner", xByCorner);
		throwIfNotFourCorners("yByCorner", yByCorner);
		throwIfNotFourCorners("zByCorner", zByCorner);
		throwIfNotFourCorners("uByCorner", uByCorner);
		throwIfNotFourCorners("vByCorner", vByCorner);
		
		int pixelCount = TextureUtil.TEXTURE_WIDTH_AND_HEIGHT * TextureUtil.TEXTURE_WIDTH_AND_HEIGHT;
		if (argbPixels == null || argbPixels.length != pixelCount)
		{
			throw new IllegalArgumentException("[argbPixels] must have ["+pixelCount+"] values, one per pixel.");
		}
		
		this.xByCorner = xByCorner;
		this.yByCorner = yByCorner;
		this.zByCorner = zByCorner;
		this.uByCorner = uByCorner;
		this.vByCorner = vByCorner;
		this.argbPixels = argbPixels;
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
