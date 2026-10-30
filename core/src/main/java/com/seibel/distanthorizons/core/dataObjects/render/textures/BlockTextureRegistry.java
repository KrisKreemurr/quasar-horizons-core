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

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateFaceTextureProvider;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.coreapi.util.ColorUtil;
import com.seibel.distanthorizons.coreapi.util.TextureUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Assigns each block state face a global atlas tile id
 * and holds the tile pixel data until it's uploaded to the GPU. <br><br>
 *
 * Tile pixels are stored as color ratios relative to the LOD's average color
 * instead of absolute colors (a gray value of 128 means the base color is used without modification).
 * This way the current tinting/shading pipeline
 * and the average color seen at a distance is unchanged. <br><br>
 *
 * Tile id {@link BlockTextureRegistry#UNTEXTURED_ID} represents "no texture"
 * and renders identically to a flat-colored LOD.
 */
public class BlockTextureRegistry
{
	private static final IBlockStateFaceTextureProvider TEXTURE_PROVIDER = SingletonInjector.INSTANCE.get(IBlockStateFaceTextureProvider.class);
	
	/** RGBA */
	public static final int TILE_BYTE_COUNT = TextureUtil.TEXTURE_WIDTH_AND_HEIGHT * TextureUtil.TEXTURE_WIDTH_AND_HEIGHT * 4;
	
	/**
	 * tile ids are stored in 16 vertex bits,
	 * if we somehow run out of ids additional faces just render flat
	 */
	public static final int MAX_TILE_COUNT = 65_536;
	
	/** renders flat */
	public static final short UNTEXTURED_ID = 0;
	/** renders every face flat, also used when set registration overflows */
	public static final short FLAT_SET_ID = 0;
	/** Fluids store their texture turned to each flow direction after their 6 faces (by EDhDirection.compassIndex) */
	public static final int FLUID_FLOW_TILE_INDEX = 6;
	
	
	// needs to be defined after the other static variables so it can reference them
	public static final BlockTextureRegistry INSTANCE = new BlockTextureRegistry();
	
	
	
	
	/** indexed by tile id, holds the ratio-encoded RGBA pixels ready for GPU upload */
	private final ArrayList<byte[]> tilePixelsById = new ArrayList<>();
	/** used to dedupe tiles so block faces sharing a texture share a tile */
	private final HashMap<TileKey, Short> tileIdByContent = new HashMap<>();
	/** tiles that haven't been uploaded to the GPU yet, drained by the render thread */
	private int firstTileIdPendingUpload = 0;
	
	/** 
	 * indexed by set id (stored by {@link BlockTextureRegistry#setIdByBlockStateWrapper}), 
	 * holds the face tiles, colors and model for one block state.
	 */
	private final ArrayList<BlockRenderSet> renderSetsById = new ArrayList<>();
	private final ConcurrentHashMap<IBlockStateWrapper, Short> setIdByBlockStateWrapper = new ConcurrentHashMap<>();
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	private BlockTextureRegistry()
	{
		// reserve the flat tile so id 0 always renders as a 1.0 multiplier
		byte[] flatPixels = new byte[TILE_BYTE_COUNT];
		
		// fill everything with a gray pixel
		for (int i = 0; i < TILE_BYTE_COUNT; i += 4)
		{
			flatPixels[i] = (byte) 128; // half of an unsigned byte, ie gray
			flatPixels[i + 1] = (byte) 128;
			flatPixels[i + 2] = (byte) 128;
			flatPixels[i + 3] = (byte) 0xFF; // max opacity (256)
		}
		this.tilePixelsById.add(flatPixels);
		
		// reserve the all-flat face set so set id 0 always renders flat
		this.renderSetsById.add(new BlockRenderSet());
	}
	
	//endregion
	
	
	
	//==============//
	// tile getters //
	//==============//
	//region
	
	/**
	 * Returns the id of the given block state's set,
	 * baking and registering its textures and model if necessary. <br>
	 * Thread safe, although baking may block briefly.
	 *
	 * @return {@link BlockTextureRegistry#FLAT_SET_ID} if the block has no textures or model
	 */
	public short getOrRegisterBlockStateSetId(IBlockStateWrapper blockState, IClientLevelWrapper levelWrapper)
	{
		Short setId = this.setIdByBlockStateWrapper.get(blockState);
		if (setId == null)
		{
			setId = this.registerBlockState(blockState, levelWrapper);
		}
		return setId;
	}
	
	/**
	 * Returns the face tiles, colors and model of the given set. <br>
	 * The returned set must not be modified.
	 *
	 * @return null if the set id is invalid or the flat set
	 */
	public synchronized @Nullable BlockRenderSet getRenderSet(int setId)
	{
		if (setId <= FLAT_SET_ID || setId >= this.renderSetsById.size())
		{
			return null;
		}
		return this.renderSetsById.get(setId);
	}
	
	/** Decides which block states are drawn with their model */
	public static boolean usesModel(IBlockStateWrapper blockState)
	{
		return (!blockState.isFullBlock() || blockState.allowApiModelOverride())
			&& !blockState.isLiquid();
	}
	
	/** Models are only drawn at block detail */
	public static boolean modelsDrawnAtDetailLevel(byte dataDetailLevel)
	{
		return Config.Client.Advanced.Graphics.Quality.enableBlockModels.get()
			&& dataDetailLevel == LodUtil.BLOCK_DETAIL_LEVEL;
	}
	
	private short registerBlockState(IBlockStateWrapper blockState, IClientLevelWrapper levelWrapper)
	{
		short[] faceTileIds = new short[6]; // 6 faces on a cube
		int[] faceColors = new int[6];
		int upAverageColor = 0;
		boolean isFluidBlock = blockState.isLiquid() && !blockState.isWaterlogged();
		// tinted blocks need the LOD's color since their own texture is gray,
		// API color overrides only change the LOD's color
		boolean useLodColor = levelWrapper.getBlockNeedsPostTinting(blockState) || blockState.allowApiColorOverride();
		boolean anyFaceTextured = false;
		
		// UP is first in EDhDirection.ALL so the sides below can fall back to it
		for (EDhDirection direction : EDhDirection.ALL)
		{
			int faceIndex = direction.faceIndex;
			BlockFaceTexture faceTexture = TEXTURE_PROVIDER.getFaceTexture(blockState, direction);
			if (faceTexture != null)
			{
				int averageColor = calculateAverageVisibleColor(faceTexture);
				faceTileIds[faceIndex] = this.getOrCreateTileId(faceTexture, averageColor);
				
				faceColors[faceIndex] = useLodColor ? BlockRenderSet.USE_LOD_COLOR : averageColor;
				
				if (direction == EDhDirection.UP)
				{
					upAverageColor = averageColor;
				}
			}
			
			// If no texture exists in the current north/sout/west/east direction,
			// try using the top instead.
			// This fixes fences, walls, anvils, etc.
			// from having untextured sides, but textured tops
			int upIndex = EDhDirection.UP.faceIndex;
			if (faceTileIds[faceIndex] == UNTEXTURED_ID
				&& faceTileIds[upIndex] != UNTEXTURED_ID
				&& direction != EDhDirection.UP
				&& direction != EDhDirection.DOWN)
			{
				faceTileIds[faceIndex] = faceTileIds[upIndex];
				faceColors[faceIndex] = faceColors[upIndex];
			}
			
			anyFaceTextured |= (faceTileIds[faceIndex] != UNTEXTURED_ID);
		}
		
		// only pillars have a top that doesn't match the LOD's color
		if (!blockState.isRotatedPillar())
		{
			faceColors[EDhDirection.UP.faceIndex] = BlockRenderSet.USE_LOD_COLOR;
		}
		
		BlockFaceTexture flowTexture = isFluidBlock ? TEXTURE_PROVIDER.getFaceTexture(blockState, EDhDirection.UP) : null;
		if (flowTexture != null)
		{
			// stripes run down the sides, like vanilla's flowing texture
			short sideTileId = this.getOrCreateTileId(flowTexture.createWithQuarterTurns(1), upAverageColor);
			for (EDhDirection direction : EDhDirection.CARDINAL_COMPASS)
			{
				faceTileIds[direction.faceIndex] = sideTileId;
			}
			
			faceTileIds = Arrays.copyOf(faceTileIds, FLUID_FLOW_TILE_INDEX + EDhDirection.CARDINAL_COMPASS.length);
			for (EDhDirection direction : EDhDirection.CARDINAL_COMPASS)
			{
				BlockFaceTexture turnedTexture = flowTexture.createWithQuarterTurns(getFlowQuarterTurns(direction));
				faceTileIds[FLUID_FLOW_TILE_INDEX + direction.compassIndex] = this.getOrCreateTileId(turnedTexture, upAverageColor);
			}
		}
		
		boolean drawModel = Config.Client.Advanced.Graphics.Quality.enableBlockModels.get() && usesModel(blockState);
		BlockModelQuad[] modelQuads = drawModel ? TEXTURE_PROVIDER.getModel(blockState) : new BlockModelQuad[0];
		short[] modelQuadTileIds = new short[modelQuads.length];
		int[] modelQuadColors = new int[modelQuads.length];
		for (int quadIndex = 0; quadIndex < modelQuads.length; quadIndex++)
		{
			BlockFaceTexture quadTexture = modelQuads[quadIndex].texture;
			int averageColor = calculateAverageVisibleColor(quadTexture);
			modelQuadTileIds[quadIndex] = this.getOrCreateTileId(quadTexture, averageColor);
			modelQuadColors[quadIndex] = useLodColor ? BlockRenderSet.USE_LOD_COLOR : averageColor;
		}
		
		short setId;
		if (!anyFaceTextured && modelQuads.length == 0)
		{
			// blocks whose every face is flat (IE uniformly colored textures)
			// share the reserved flat set
			setId = FLAT_SET_ID;
		}
		else
		{
			// synchronized to prevent concurrent array modifications
			synchronized (this)
			{
				if (this.renderSetsById.size() >= MAX_TILE_COUNT)
				{
					setId = FLAT_SET_ID;
				}
				else
				{
					setId = (short) this.renderSetsById.size();
					this.renderSetsById.add(new BlockRenderSet(faceTileIds, faceColors, modelQuads, modelQuadTileIds, modelQuadColors, blockState));
				}
			}
		}
		
		Short existingSetId = this.setIdByBlockStateWrapper.putIfAbsent(blockState, setId);
		return (existingSetId != null) ? existingSetId : setId;
	}
	
	private static int getFlowQuarterTurns(EDhDirection flowDirection)
	{
		switch (flowDirection)
		{
			case SOUTH: return 1;
			case EAST: return 2;
			case NORTH: return 3;
			default: return 0;
		}
	}
	
	//endregion
	
	
	
	//===================//
	// tile registration //
	//===================//
	//region
	
	private synchronized short getOrCreateTileId(BlockFaceTexture faceTexture, int averageColor)
	{
		byte[] ratioPixels = convertColorsToDifferenceRatios(faceTexture, averageColor);
		if (ratioPixels == null)
		{
			return UNTEXTURED_ID;
		}
		
		TileKey key = new TileKey(ratioPixels);
		Short existingId = this.tileIdByContent.get(key);
		if (existingId != null)
		{
			return existingId;
		}
		
		if (this.tilePixelsById.size() >= MAX_TILE_COUNT)
		{
			return UNTEXTURED_ID;
		}
		
		short newId = (short) this.tilePixelsById.size();
		this.tilePixelsById.add(ratioPixels);
		this.tileIdByContent.put(key, newId);
		return newId;
	}
	
	/** @return the average of the visible pixels, or {@link ColorUtil#INVISIBLE} if none are visible */
	private static int calculateAverageVisibleColor(BlockFaceTexture faceTexture)
	{
		int[] argbPixels = faceTexture.argbPixels;
		
		// average only the visible pixels, otherwise
		// cutout textures (IE fences) would have overly dark averages
		long redSum = 0;
		long greenSum = 0;
		long blueSum = 0;
		int visibleCount = 0;
		for (int i = 0; i < argbPixels.length; i++)
		{
			if (ColorUtil.getAlpha(argbPixels[i]) != 0)
			{
				redSum += ColorUtil.getRed(argbPixels[i]);
				greenSum += ColorUtil.getGreen(argbPixels[i]);
				blueSum += ColorUtil.getBlue(argbPixels[i]);
				visibleCount++;
			}
		}
		if (visibleCount == 0)
		{
			return ColorUtil.INVISIBLE;
		}
		
		return ColorUtil.rgbToInt((int) (redSum / visibleCount), (int) (greenSum / visibleCount), (int) (blueSum / visibleCount));
	}
	
	/**
	 * Converts the texture's absolute colors into ratios
	 * relative to the texture's average color.
	 * 128 = 1.0, 255 = 2.0, ratios above 2.0 are clamped.
	 *
	 * @return null if the texture is a single uniform color, IE flat shading is identical
	 */
	private static byte[] convertColorsToDifferenceRatios(BlockFaceTexture faceTexture, int averageColor)
	{
		int[] argbPixels = faceTexture.argbPixels;
		
		if (ColorUtil.getAlpha(averageColor) == 0)
		{
			return null;
		}
		
		float averageRed = Math.max(ColorUtil.getRed(averageColor), 1.0f);
		float averageGreen = Math.max(ColorUtil.getGreen(averageColor), 1.0f);
		float averageBlue = Math.max(ColorUtil.getBlue(averageColor), 1.0f);
		
		
		byte[] uploadPixels = new byte[TILE_BYTE_COUNT];
		boolean anyPixelDiffersFromAverage = false;
		for (int v = 0; v < TextureUtil.TEXTURE_WIDTH_AND_HEIGHT; v++)
		{
			for (int u = 0; u < TextureUtil.TEXTURE_WIDTH_AND_HEIGHT; u++)
			{
				// 1x1 fallback tiles repeat their single pixel
				int sourceIndex = ((v * faceTexture.height / TextureUtil.TEXTURE_WIDTH_AND_HEIGHT) * faceTexture.width)
						+ (u * faceTexture.width / TextureUtil.TEXTURE_WIDTH_AND_HEIGHT);
				int argb = argbPixels[sourceIndex];
				
				byte red, green, blue, alpha;
				if (faceTexture.uploadAsColorRatio)
				{
					// upload as a ratio so the texture modifies the base DH defined color
					red   = encodeRatio(ColorUtil.getRed(argb), averageRed);
					green = encodeRatio(ColorUtil.getGreen(argb), averageGreen);
					blue  = encodeRatio(ColorUtil.getBlue(argb), averageBlue);
					alpha = (byte)ColorUtil.getAlpha(argb);
				}
				else
				{
					// upload as an absolute color
					// (due to how rendering is done this will only partially work,
					// but is helpful for the error texture to appear correctly)
					red   = (byte) ColorUtil.getRed(argb);
					green = (byte) ColorUtil.getGreen(argb);
					blue  = (byte) ColorUtil.getBlue(argb);
					alpha = (byte) ColorUtil.getAlpha(argb);
				}
				
				int outIndex = TextureUtil.getPixelIndex(u,v) * 4;
				uploadPixels[outIndex] = red;
				uploadPixels[outIndex + 1] = green;
				uploadPixels[outIndex + 2] = blue;
				uploadPixels[outIndex + 3] = alpha;
				
				
				// only check if the pixel differs when the alpha is visible (non-zero)
				if (alpha != 0)
				{
					// 128 is gray (half-way between 0 and 256)
					byte averageGray = (byte) 128;
					
					// we only care about the texture if it differs from the average block color 
					// (represented by gray in the uploaded texture)
					anyPixelDiffersFromAverage |=
						red != averageGray
						|| green != averageGray
						|| blue != averageGray;
				}
			}
		}
		
		return anyPixelDiffersFromAverage ? uploadPixels : null;
	}
	private static byte encodeRatio(int channel, float average)
	{
		int encoded = Math.round((channel / average) * 127.5f);
		return (byte) Math.min(encoded, 255);
	}
	
	//endregion
	
	
	
	//============//
	// GPU upload //
	//============//
	//region
	
	/**
	 * Returns the tiles registered since the last call so they can
	 * be uploaded to the GPU, must be called from the render thread.
	 */
	public synchronized PendingTiles getAndClearPendingUploadTiles()
	{
		int firstId = this.firstTileIdPendingUpload;
		int tileCount = this.tilePixelsById.size() - firstId;
		if (tileCount <= 0)
		{
			return null;
		}
		
		byte[][] pixelArrays = new byte[tileCount][];
		for (int i = 0; i < tileCount; i++)
		{
			pixelArrays[i] = this.tilePixelsById.get(firstId + i);
		}
		this.firstTileIdPendingUpload = this.tilePixelsById.size();
		return new PendingTiles(firstId, pixelArrays);
	}
	
	public synchronized int getTileCount() { return this.tilePixelsById.size(); }
	
	/** Makes every tile pending again, used when the GPU atlas is re-allocated. */
	public synchronized void resetPendingUploads() { this.firstTileIdPendingUpload = 0; }
	
	/** Should be called whenever MC's textures change, IE when resource packs are swapped. */
	public synchronized void clear()
	{
		this.setIdByBlockStateWrapper.clear();
		BlockRenderSet flatSet = this.renderSetsById.get(FLAT_SET_ID);
		this.renderSetsById.clear();
		this.renderSetsById.add(flatSet);
		
		this.tileIdByContent.clear();
		byte[] flatTile = this.tilePixelsById.get(UNTEXTURED_ID);
		this.tilePixelsById.clear();
		this.tilePixelsById.add(flatTile);
		this.firstTileIdPendingUpload = 0;
		
		IBlockStateFaceTextureProvider textureProvider = SingletonInjector.INSTANCE.get(IBlockStateFaceTextureProvider.class);
		if (textureProvider != null)
		{
			textureProvider.clear();
		}
	}
	
	//endregion
	
	
	
	//================//
	// helper classes //
	//================//
	//region
	
	/** wraps tile pixels so they can be used as a hash map key for deduplication */
	private static class TileKey
	{
		private final byte[] pixels;
		private final int hash;
		
		public TileKey(byte[] pixels)
		{
			this.pixels = pixels;
			this.hash = Arrays.hashCode(pixels);
		}
		
		@Override
		public int hashCode() { return this.hash; }
		
		@Override
		public boolean equals(Object obj)
		{
			if (this == obj) 
			{
				return true; 
			}
			
			if (!(obj instanceof TileKey)) 
			{
				return false; 
			}
			
			return Arrays.equals(this.pixels, ((TileKey) obj).pixels);
		}
		
	}
	
	public static class PendingTiles
	{
		public final int firstTileId;
		public final byte[][] tilePixels;
		
		public PendingTiles(int firstTileId, byte[][] tilePixels)
		{
			this.firstTileId = firstTileId;
			this.tilePixels = tilePixels;
		}
	}
	
	//endregion
	
	
	
}
