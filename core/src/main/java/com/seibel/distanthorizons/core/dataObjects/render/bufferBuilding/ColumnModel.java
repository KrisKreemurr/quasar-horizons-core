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

package com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding;

import com.seibel.distanthorizons.api.enums.rendering.EDhApiTransparency;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.render.columnViews.ColumnRenderView;
import com.seibel.distanthorizons.core.dataObjects.render.textures.BlockRenderRegistry;
import com.seibel.distanthorizons.core.dataObjects.render.textures.BlockRenderSet;
import com.seibel.distanthorizons.core.dataObjects.render.textures.DhBlockModelQuad;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.RenderDataPointUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.coreapi.util.ColorUtil;
import org.jetbrains.annotations.Nullable;

/** Draws a data point with its block model */
public class ColumnModel
{
	/** False for models without a full face on that side */
	static boolean hasFullFace(@Nullable BlockRenderSet set, EDhDirection side)
	{
		return set == null
			|| set.modelQuads.length == 0
			|| set.blockState.isFaceFull(side);
	}
	
	/** Transparent neighbors never hide quads */
	private static boolean neighborHidesSide(long neighbor, @Nullable BlockRenderSet neighborSet, BlockRenderSet modelSet, EDhDirection side, boolean transparencyEnabled)
	{
		return RenderDataPointUtil.doesDataPointExist(neighbor)
			&& !(transparencyEnabled && RenderDataPointUtil.getAlpha(neighbor) < 255)
			&& (hasFullFace(neighborSet, side.opposite()) || modelSet.isSideHiddenBy(neighborSet, side));
	}
	
	/** Fills in whether that side hides this block's quads and the sky light coming from it */
	private static void checkSide(
		EDhDirection side, short blockY, BlockRenderSet modelSet, short pointMinY, short pointMaxY, byte skyLight,
		long topData, long bottomData, @Nullable BlockRenderSet topSet, @Nullable BlockRenderSet bottomSet,
		ColumnRenderView[] adjColumnViews, AdjacentColumnSets adjColumnSets, boolean transparencyEnabled,
		boolean[] hiddenSides, byte[] sideLights)
	{
		int faceIndex = side.faceIndex;
		EDhDirection neighborSide = side.opposite();
		if (side == EDhDirection.UP)
		{
			sideLights[faceIndex] = skyLight;
			// stacked blocks in the same point
			hiddenSides[faceIndex] = (blockY + 1 < pointMaxY)
				? modelSet.blockState.isFaceFull(neighborSide) || modelSet.isSideHiddenBy(modelSet, side)
				: RenderDataPointUtil.getYMin(topData) == pointMaxY && neighborHidesSide(topData, topSet, modelSet, side, transparencyEnabled);
			return;
		}
		else if (side == EDhDirection.DOWN)
		{
			sideLights[faceIndex] = RenderDataPointUtil.doesDataPointExist(bottomData) ? RenderDataPointUtil.getLightSky(bottomData) : LodUtil.MIN_MC_LIGHT;
			hiddenSides[faceIndex] = (blockY > pointMinY)
				? modelSet.blockState.isFaceFull(neighborSide) || modelSet.isSideHiddenBy(modelSet, side)
				: RenderDataPointUtil.getYMax(bottomData) == pointMinY && neighborHidesSide(bottomData, bottomSet, modelSet, side, transparencyEnabled);
			return;
		}
		
		// the neighbor column is sorted top down
		ColumnRenderView adjColumn = adjColumnViews[side.compassIndex];
		for (int adjIndex = 0; adjIndex < adjColumn.size; adjIndex++)
		{
			long adjPoint = adjColumn.get(adjIndex);
			if (!RenderDataPointUtil.doesDataPointExist(adjPoint))
			{
				break;
			}
			
			// the air above a lower point
			if (RenderDataPointUtil.getYMax(adjPoint) <= blockY)
			{
				hiddenSides[faceIndex] = false;
				sideLights[faceIndex] = RenderDataPointUtil.getLightSky(adjPoint);
				return;
			}
			
			if (RenderDataPointUtil.getYMin(adjPoint) <= blockY)
			{
				hiddenSides[faceIndex] = neighborHidesSide(adjPoint, adjColumnSets.getDataPointRenderSet(side, adjIndex), modelSet, side, transparencyEnabled);
				sideLights[faceIndex] = hiddenSides[faceIndex] ? LodUtil.MIN_MC_LIGHT : RenderDataPointUtil.getLightSky(adjPoint);
				return;
			}
		}
		
		hiddenSides[faceIndex] = false;
		sideLights[faceIndex] = LodUtil.MAX_MC_LIGHT;
	}
	
	public static void addModelQuadsToBuilder(
		LodQuadBuilder quadBuilder, IClientLevelWrapper clientLevelWrapper,
		BlockRenderSet modelSet, boolean useTextures, boolean useQuadColors,
		short blockMinX, short blockMinY, short blockMinZ, short height,
		int color, byte irisBlockMaterialId, byte skyLight, byte blockLight,
		long topData, long bottomData, @Nullable BlockRenderSet topSet, @Nullable BlockRenderSet bottomSet,
		ColumnRenderView[] adjColumnViews, AdjacentColumnSets adjColumnSets, boolean[] hiddenSides, byte[] sideLights)
	{
		float saturationMultiplier = Config.Client.Advanced.Graphics.Quality.saturationMultiplier.get();
		float brightnessMultiplier = Config.Client.Advanced.Graphics.Quality.brightnessMultiplier.get();
		boolean transparencyEnabled = Config.Client.Advanced.Graphics.Quality.transparency.get() == EDhApiTransparency.COMPLETE;
		short pointMaxY = (short) (blockMinY + height);
		
		// the model is drawn per block of the point
		for (short blockY = (short) (pointMaxY - 1); blockY >= blockMinY; blockY--)
		{
			for (EDhDirection side : EDhDirection.ALL)
			{
				checkSide(side, blockY, modelSet, blockMinY, pointMaxY, skyLight,
					topData, bottomData, topSet, bottomSet, adjColumnViews, adjColumnSets, transparencyEnabled,
					hiddenSides, sideLights);
			}
			
			byte brightestLight = LodUtil.MIN_MC_LIGHT;
			for (byte sideLight : sideLights)
			{
				brightestLight = (byte) Math.max(brightestLight, sideLight);
			}
			
			for (int quadIndex = 0; quadIndex < modelSet.modelQuads.length; quadIndex++)
			{
				DhBlockModelQuad modelQuad = modelSet.modelQuads[quadIndex];
				if (modelQuad.cullDirection != null
					&& hiddenSides[modelQuad.cullDirection.faceIndex])
				{
					continue;
				}
				
				int quadColor = color;
				if (useQuadColors
					&& modelSet.modelQuadColors[quadIndex] != BlockRenderSet.USE_LOD_COLOR)
				{
					// the LOD's alpha is kept
					quadColor = ColumnRenderBufferBuilder.applyColorSettings(modelSet.modelQuadColors[quadIndex], saturationMultiplier, brightnessMultiplier);
					quadColor = ColorUtil.setAlpha(quadColor, ColorUtil.getAlpha(color));
				}
				quadColor = ColorUtil.applyShade(quadColor, clientLevelWrapper.getShade(modelQuad.shadeDirection));
				
				short tileId = useTextures ? modelSet.modelQuadTileIds[quadIndex] : BlockRenderRegistry.UNTEXTURED_ID;
				
				// quads inside the block are lit by its brightest side
				byte quadSkyLight = (modelQuad.cullDirection != null) ? sideLights[modelQuad.cullDirection.faceIndex] : brightestLight;
				
				quadBuilder.addModelQuad(modelQuad, blockMinX, blockY, blockMinZ, quadColor, tileId, irisBlockMaterialId, quadSkyLight, blockLight);
			}
		}
	}
	
}
