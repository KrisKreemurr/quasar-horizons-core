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

package com.seibel.distanthorizons.core.config.eventHandlers;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.listeners.IConfigListener;

/** Turns non-colliding block models off when block models are turned off */
public class BlockModelConfigEventHandler implements IConfigListener
{
	public static BlockModelConfigEventHandler INSTANCE = new BlockModelConfigEventHandler();
	
	
	
	/** private since we only ever need one handler at a time */
	private BlockModelConfigEventHandler() { }
	
	
	
	@Override
	public void onConfigValueSet()
	{
		if (!Config.Client.Advanced.Graphics.Quality.enableBlockModels.get()
			&& Config.Client.Advanced.Graphics.Quality.enableNonCollidingBlockModels.get())
		{
			Config.Client.Advanced.Graphics.Quality.enableNonCollidingBlockModels.set(false);
		}
	}
	
	@Override
	public void onUiModify() { /* do nothing, we only care about modified config values */ }
	
}
