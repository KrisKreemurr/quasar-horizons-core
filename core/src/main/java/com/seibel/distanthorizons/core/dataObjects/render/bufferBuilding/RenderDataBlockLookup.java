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

import com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource;
import com.seibel.distanthorizons.core.dataObjects.render.columnViews.ColumnRenderView;
import com.seibel.distanthorizons.core.dataObjects.render.textures.BlockRenderSet;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.util.RenderDataPointUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.ILodBlockLookup;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import org.jetbrains.annotations.Nullable;

/**
 * Reads the block states around the current column from the render data being built,
 * IE its own column and the four adjacent ones. <br>
 * Merged data points read as their top block, so this isn't exact for columns reduced by the vertical quality.
 *
 * @see ColumnModel
 */
public class RenderDataBlockLookup implements ILodBlockLookup
{
	private static final IBlockStateWrapper AIR = SingletonInjector.INSTANCE.get(IWrapperFactory.class).getAirBlockStateWrapper();

	private final IClientLevelWrapper levelWrapper;
	private final ColumnRenderSource renderSource;
	private final BlockRenderSet @Nullable [] setsByPaletteIndex;
	private final ColumnRenderView columnView;
	private final ColumnRenderView[] adjColumnViews;
	private final AdjacentColumnSets adjColumnSets;

	private int relX;
	private int relZ;
	private int blockX;
	private int blockZ;



	public RenderDataBlockLookup(
		IClientLevelWrapper levelWrapper, ColumnRenderSource renderSource, BlockRenderSet @Nullable [] setsByPaletteIndex,
		ColumnRenderView columnView, ColumnRenderView[] adjColumnViews, AdjacentColumnSets adjColumnSets)
	{
		this.levelWrapper = levelWrapper;
		this.renderSource = renderSource;
		this.setsByPaletteIndex = setsByPaletteIndex;
		this.columnView = columnView;
		this.adjColumnViews = adjColumnViews;
		this.adjColumnSets = adjColumnSets;
	}

	/** has to be called once the views are populated for the column */
	public void setColumn(int relX, int relZ, int blockX, int blockZ)
	{
		this.relX = relX;
		this.relZ = relZ;
		this.blockX = blockX;
		this.blockZ = blockZ;
	}



	@Override
	public ILevelWrapper getLevelWrapper() { return this.levelWrapper; }

	@Override
	@Nullable
	public IBlockStateWrapper getBlockState(int blockX, int blockY, int blockZ)
	{
		int offsetX = blockX - this.blockX;
		int offsetZ = blockZ - this.blockZ;

		EDhDirection direction;
		if (offsetX == 0 && offsetZ == 0)
		{
			direction = null;
		}
		else if (offsetZ == 0 && (offsetX == 1 || offsetX == -1))
		{
			direction = (offsetX < 0) ? EDhDirection.WEST : EDhDirection.EAST;
		}
		else if (offsetX == 0 && (offsetZ == 1 || offsetZ == -1))
		{
			direction = (offsetZ < 0) ? EDhDirection.NORTH : EDhDirection.SOUTH;
		}
		else
		{
			// diagonal columns aren't loaded
			return null;
		}

		if (direction != null && !this.adjColumnSets.hasSets(direction))
		{
			return null;
		}

		ColumnRenderView view = (direction == null) ? this.columnView : this.adjColumnViews[direction.compassIndex];
		if (view.size == 0 || !RenderDataPointUtil.doesDataPointExist(view.get(0)))
		{
			return null;
		}

		// the column is sorted top down
		int relY = blockY - this.levelWrapper.getMinHeight();
		for (int index = 0; index < view.size; index++)
		{
			long dataPoint = view.get(index);
			if (!RenderDataPointUtil.doesDataPointExist(dataPoint))
			{
				break;
			}

			// the air above a lower point
			if (RenderDataPointUtil.getYMax(dataPoint) <= relY)
			{
				return AIR;
			}

			if (RenderDataPointUtil.getYMin(dataPoint) <= relY)
			{
				// blocks without textures or a model don't have a set
				BlockRenderSet set = (direction == null)
					? ColumnRenderBufferBuilder.getDataPointRenderSet(this.setsByPaletteIndex, this.renderSource, this.relX, this.relZ, index)
					: this.adjColumnSets.getDataPointRenderSet(direction, index);
				return (set != null) ? set.blockState : null;
			}
		}

		// below the lowest point
		return AIR;
	}

}
