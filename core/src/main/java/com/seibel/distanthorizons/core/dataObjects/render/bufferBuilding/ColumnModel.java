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

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.render.textures.BlockModelQuad;
import com.seibel.distanthorizons.core.dataObjects.render.textures.BlockTextureRegistry;
import com.seibel.distanthorizons.core.dataObjects.render.textures.BlockTextureSet;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.coreapi.util.ColorUtil;

/** Draws a data point with its block model, the model counterpart to {@link ColumnBox} */
public class ColumnModel
{
	public static void addModelQuadsToBuilder(
		LodQuadBuilder quadBuilder, IClientLevelWrapper clientLevelWrapper,
		BlockTextureSet modelSet, boolean useTextures, boolean useQuadColors,
		short blockMinX, short blockMinY, short blockMinZ, short height,
		int color, byte irisBlockMaterialId, byte skyLight, byte blockLight)
	{
		float saturationMultiplier = Config.Client.Advanced.Graphics.Quality.saturationMultiplier.get();
		float brightnessMultiplier = Config.Client.Advanced.Graphics.Quality.brightnessMultiplier.get();
		
		for (int quadIndex = 0; quadIndex < modelSet.modelQuads.length; quadIndex++)
		{
			BlockModelQuad modelQuad = modelSet.modelQuads[quadIndex];
			
			int quadColor = color;
			if (useQuadColors
				&& modelSet.modelQuadColors[quadIndex] != BlockTextureSet.USE_LOD_COLOR)
			{
				// the LOD's alpha is kept so transparency doesn't change
				quadColor = ColumnRenderBufferBuilder.applyColorSettings(modelSet.modelQuadColors[quadIndex], saturationMultiplier, brightnessMultiplier);
				quadColor = ColorUtil.setAlpha(quadColor, ColorUtil.getAlpha(color));
			}
			quadColor = ColorUtil.applyShade(quadColor, clientLevelWrapper.getShade(modelQuad.shadeDirection));
			
			short tileId = useTextures ? modelSet.modelQuadTileIds[quadIndex] : BlockTextureRegistry.UNTEXTURED_ID;
			
			// stacked blocks are one point, the model is drawn per block
			for (short blockY = blockMinY; blockY < blockMinY + height; blockY++)
			{
				quadBuilder.addModelQuad(modelQuad, blockMinX, blockY, blockMinZ, quadColor, tileId, irisBlockMaterialId, skyLight, blockLight);
			}
		}
	}
	
}
