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

package com.seibel.distanthorizons.core.sql.dto;

import com.google.common.base.MoreObjects;
import com.seibel.distanthorizons.api.enums.config.EDhApiDataCompressionMode;
import com.seibel.distanthorizons.api.enums.config.EDhApiWorldCompressionMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.api.interfaces.override.datastore.IDhApiDatastore;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.network.INetworkObject;
import com.seibel.distanthorizons.core.pooling.PhantomArrayListParent;
import com.seibel.distanthorizons.core.pooling.PhantomArrayListPool;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.BoolUtil;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import com.seibel.distanthorizons.core.util.ListUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.objects.DataCorruptedException;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataInputStream;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataOutputStream;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.util.Arrays;

/**
 * handles storing {@link FullDataSourceV2}'s in the database.
 */
public class FullDataSourceV2DTO
		extends PhantomArrayListParent
		implements IBaseDTO<Long>, INetworkObject, AutoCloseable {
	public static final boolean VALIDATE_INPUT_DATAPOINTS = true;

	public long pos;

	public int levelMinY;

	/**
	 * only for the data array
	 */
	public int dataChecksum;

	public ByteArrayList dataByteArray;

	/**
	 * @see EDhApiWorldGenerationStep
	 */
	public ByteArrayList columnGenStepByteArray;
	/**
	 * @see EDhApiWorldCompressionMode
	 */
	public ByteArrayList worldCompressionModeByteArray;

	public ByteArrayList mappingByteArray;

	public byte dataFormatVersion;
	
	/** Will be null if we don't want to update this value in the DB */
	@Nullable
	public Boolean applyToParent;
	/** Will be null if we don't want to update this value in the DB */
	@Nullable
	public Boolean applyToChildren;
	
	public long lastModifiedUnixDateTime;
	public long createdUnixDateTime;


	public static final PhantomArrayListPool ARRAY_LIST_POOL = new PhantomArrayListPool("V2DTO");

	public IDhApiDatastore.FullData toDatastoreFullData(int levelId) {
		IDhApiDatastore.FullData datastoreFullData = new IDhApiDatastore.FullData();

		datastoreFullData.levelId = levelId;
		datastoreFullData.detailLevel = DhSectionPos.getDetailLevel(pos);
		datastoreFullData.posX = DhSectionPos.getX(pos);
		datastoreFullData.posZ = DhSectionPos.getZ(pos);
		datastoreFullData.minY = levelMinY;
		datastoreFullData.dataChecksum = dataChecksum;
		datastoreFullData.data = dataByteArray.elements();
		datastoreFullData.columnGenerationStep = columnGenStepByteArray.elements();
		datastoreFullData.columnWorldCompressionMode = worldCompressionModeByteArray.elements();
		datastoreFullData.mapping = mappingByteArray.elements();
		datastoreFullData.dataFormatVersion = dataFormatVersion;
		datastoreFullData.applyToParent = applyToParent;

		return datastoreFullData;
	}

	public FullDataSourceV2DTO(IDhApiDatastore.FullData datastoreFullData) {
		super(ARRAY_LIST_POOL, 0, 0, 0);
		this.pos = DhSectionPos.encode(datastoreFullData.detailLevel, datastoreFullData.posX, datastoreFullData.posZ);
		this.levelMinY = datastoreFullData.minY;
		this.dataChecksum = datastoreFullData.dataChecksum;
		this.dataByteArray = new ByteArrayList(datastoreFullData.data);
		this.columnGenStepByteArray = new ByteArrayList(datastoreFullData.columnGenerationStep);
		this.worldCompressionModeByteArray = new ByteArrayList(datastoreFullData.columnWorldCompressionMode);
		this.mappingByteArray = new ByteArrayList(datastoreFullData.mapping);
		this.dataFormatVersion = datastoreFullData.dataFormatVersion;
		this.applyToParent = datastoreFullData.applyToParent;
		this.lastModifiedUnixDateTime = datastoreFullData.lastModified.getTime();
		this.createdUnixDateTime = datastoreFullData.created.getTime();
	}

	//==============//
	// constructors //
	//==============//

	public static FullDataSourceV2DTO CreateFromDataSource(FullDataSourceV2 dataSource) throws IOException {
		FullDataSourceV2DTO dto = FullDataSourceV2DTO.CreateEmptyDataSourceForDecoding();

		// populate arrays
		writeDataSourceDataArrayToBlob(dataSource.dataPoints, dto.dataByteArray);
		writeGenerationStepsToBlob(dataSource.columnGenerationSteps, dto.columnGenStepByteArray);
		writeWorldCompressionModeToBlob(dataSource.columnWorldCompressionMode, dto.worldCompressionModeByteArray);
		writeDataMappingToBlob(dataSource.mapping, dto.mappingByteArray);

		// populate individual variables
		{
			dto.pos = dataSource.getPos();
			// the mapping hash isn't included since it takes significantly longer to calculate and 
			// as of the time of this comment (2025-1-22) the checksum isn't used for anything so changing it shouldn't cause any issues
			dto.dataChecksum = dataSource.hashCode();
			dto.dataFormatVersion = FullDataSourceV2.DATA_FORMAT_VERSION;
			dto.lastModifiedUnixDateTime = dataSource.lastModifiedUnixDateTime;
			dto.createdUnixDateTime = dataSource.createdUnixDateTime;
			dto.applyToParent = dataSource.applyToParent;
			dto.applyToChildren = dataSource.applyToChildren;
			dto.levelMinY = dataSource.levelMinY;
		}

		return dto;
	}

	/**
	 * Should only be used for subsequent decoding
	 */
	public static FullDataSourceV2DTO CreateEmptyDataSourceForDecoding() {
		return new FullDataSourceV2DTO();
	}

	private FullDataSourceV2DTO() {
		super(ARRAY_LIST_POOL, 4, 0, 0);

		// Expected sizes here are 0 since we don't know how big these arrays need to be,
		// they depend on compression settings and world complexity.
		this.dataByteArray = this.pooledArraysCheckout.getByteArray(0, 0);
		this.columnGenStepByteArray = this.pooledArraysCheckout.getByteArray(1, 0);
		this.worldCompressionModeByteArray = this.pooledArraysCheckout.getByteArray(2, 0);
		this.mappingByteArray = this.pooledArraysCheckout.getByteArray(3, 0);
	}

	//========================//
	// data source population //
	//========================//

	public FullDataSourceV2 createDataSource(@NotNull ILevelWrapper levelWrapper) throws IOException, InterruptedException, DataCorruptedException {
		FullDataSourceV2 dataSource = FullDataSourceV2.createEmpty(this.pos);
		this.internalPopulateDataSource(dataSource, levelWrapper, false);
		return dataSource;
	}

	/**
	 * May be missing one or more data fields. <br>
	 * Designed to be used without access to Minecraft or any supporting objects.
	 */
	public FullDataSourceV2 createUnitTestDataSource() throws IOException, InterruptedException, DataCorruptedException {
		return this.internalPopulateDataSource(FullDataSourceV2.createEmpty(this.pos), null, true);
	}

	private FullDataSourceV2 internalPopulateDataSource(FullDataSourceV2 dataSource, ILevelWrapper levelWrapper, boolean unitTest) throws IOException, InterruptedException, DataCorruptedException {
		if (FullDataSourceV2.DATA_FORMAT_VERSION != this.dataFormatVersion) {
			throw new IllegalStateException("There should only be one data format [" + FullDataSourceV2.DATA_FORMAT_VERSION + "].");
		}

		readBlobToGenerationSteps(this.columnGenStepByteArray, dataSource.columnGenerationSteps);
		readBlobToWorldCompressionMode(this.worldCompressionModeByteArray, dataSource.columnWorldCompressionMode);
		readBlobToDataSourceDataArray(this.dataByteArray, dataSource.dataPoints);

		dataSource.mapping.clear(dataSource.getPos());
		// should only be null when used in a unit test
		if (!unitTest) {
			if (levelWrapper == null) {
				throw new NullPointerException("No level wrapper present, unable to deserialize data map. This should only be used for unit tests.");
			}

			FullDataPointIdMap newMap = readBlobToDataMapping(this.mappingByteArray, dataSource.getPos(), levelWrapper);
			dataSource.mapping.addAll(newMap);
			if (dataSource.mapping.size() != newMap.size()) {
				// if the mappings are out of sync then the LODs will render incorrectly due to IDs being wrong
				LodUtil.assertNotReach("ID maps out of sync for pos: " + this.pos);
			}
		}

		dataSource.lastModifiedUnixDateTime = this.lastModifiedUnixDateTime;
		dataSource.createdUnixDateTime = this.createdUnixDateTime;

		dataSource.levelMinY = this.levelMinY;

		dataSource.isEmpty = false;

		
		if (this.applyToParent != null)
		{
			dataSource.applyToParent = this.applyToParent;
		}
		if (this.applyToChildren != null)
		{
			dataSource.applyToChildren = this.applyToChildren;
		}
		
		return dataSource;
	}


	//=================//
	// (de)serializing //
	//=================//

	private static void writeDataSourceDataArrayToBlob(LongArrayList[] inputDataArray, ByteArrayList outputByteArray) throws IOException {
		// write the outputs to a stream to prep for writing to the database
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		
		// normally a DhStream should be the topmost stream to prevent closing the stream accidentally, 
		// but since this stream will be closed immediately after writing anyway, it won't be an issue
		DhDataOutputStream outputStream = new DhDataOutputStream(byteArrayOutputStream, EDhApiDataCompressionMode.UNCOMPRESSED);

		outputStream.writeShort(-1);
		encodeFullDataPoints(outputStream, inputDataArray);

		// generate the checksum
		outputStream.flush();
		byteArrayOutputStream.close();
		outputByteArray.addElements(0, byteArrayOutputStream.toByteArray());
	}

	public static void encodeFullDataPoints(DhDataOutputStream out, LongArrayList[] inputDataArray) throws IOException {
		assert inputDataArray.length == FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH;

		// get all unique block states and send them.
		// then, when sending data points, we can send the index of this array instead.
		// this is not necessarily a helpful method for reducing serialized size,
		// but it does seem to be effective in this case, and
		// I put that down to the fact that block states can be fairly large numbers,
		// while also containing only a smaller subset of possible block states in any given section.
		int[] ids = Arrays.stream(inputDataArray).flatMapToInt(l -> l.stream().mapToInt(Long::intValue)).toArray();
		int uniqueIds = getOrderedUniques(ids);
		out.writeVarUInt(uniqueIds);
		for (int i = 0; i < uniqueIds; i++) out.writeVarUInt(ids[i]);

		for (int i = 0; i < inputDataArray.length; i++) {
			LongArrayList array = inputDataArray[i];
			if (array == null || array.isEmpty()) {
				out.writeVarUInt(0);

				// if there's a bunch of empty arrays, just write how many empty arrays there are
				int emptyCount = 0;
				while (array == null || array.isEmpty()) {
					emptyCount++;
					i++;

					if (i >= inputDataArray.length) {
						out.writeVarUInt(emptyCount);
						return;
					}
					array = inputDataArray[i];
				}
				out.writeVarUInt(emptyCount);
			}

			// write the size of this array
			out.writeVarUInt(array.size());

			// bottom y always decreases by height for each subsequent full data point.
			// so write the starting value and let the deserializer decrement it as it goes.
			out.writeVarUInt(FullDataPointUtil.getBottomY(array.getLong(0)) + FullDataPointUtil.getHeight(array.getLong(0)));

			int last_light = 0;
			for (long data : array) {
				// get light and height
				int height = FullDataPointUtil.getHeight(data);
				int light = FullDataPointUtil.getBlockLight(data) | FullDataPointUtil.getSkyLight(data) << 4;
				int light_delta = last_light ^ light;
				last_light = light;

				// no-as-dumb-as-it-looks method for finding the block state id.
				// this array is ordered from most common to least common, and is not particularly long.
				// while this is done for each datapoint, it uses no memory and the JVM and branch predictor will love it.
				// this is fast (source: trust me)
				int id_index = 0;
				while (ids[id_index] != FullDataPointUtil.getId(data)) id_index++;
				out.writeVarUInt(id_index << 2 | (light_delta == 0 ? 0 : (1<<1)) | (height == 1 ? 0 : 1));

				// write height - only if it's not 1
				if (height != 1)
					out.writeVarUInt(height);

				// write light - only if it's changed
				// only write the delta of light - the compression algorithms like it.
				if (light_delta != 0)
					out.write(light_delta);
			}
		}
	}

	private static void readBlobToDataSourceDataArray(ByteArrayList inputCompressedDataByteArray, LongArrayList[] outputDataLongArray) throws IOException, DataCorruptedException {
		ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(inputCompressedDataByteArray.elements());
		DhDataInputStream inputStream = new DhDataInputStream(byteArrayInputStream,EDhApiDataCompressionMode.UNCOMPRESSED);


		// read the data
		int dataArrayLength = FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH;
		for (int xz = 0; xz < dataArrayLength; xz++) {
			// read the column length
			short dataColumnLength = inputStream.readShort(); // separate variables are used for debugging and in case validation wants to be added later
			if (dataColumnLength < 0) {
				if (xz == 0 && dataColumnLength == -1) {
					decodeFullDataPoints(inputStream, outputDataLongArray);
					return;
				}
				throw new DataCorruptedException("Read DataSource Blob data at index [" + xz + "], column length [" + dataColumnLength + "] should be greater than zero.");
			}

			LongArrayList dataColumn = outputDataLongArray[xz];
			ListUtil.clearAndSetSize(dataColumn, dataColumnLength);

			// read column data (will be skipped if no data was present)
			for (int y = 0; y < dataColumnLength; y++) {
				long dataPoint = inputStream.readLong();
				if (VALIDATE_INPUT_DATAPOINTS) {
					FullDataPointUtil.validateDatapoint(dataPoint);
				}
				dataColumn.set(y, dataPoint);
			}
		}
	}

	public static void decodeFullDataPoints(DhDataInputStream in, LongArrayList[] outputDataLongArray) throws IOException, DataCorruptedException {
		assert outputDataLongArray.length == FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH;

		// read the block states
		int[] ids = new int[in.readVarUint()];
		for (int i = 0; i < ids.length; i++) ids[i] = in.readVarUint();

		for (int i = 0; i < outputDataLongArray.length; i++) {
			int size = in.readVarUint();
			if (size == 0) {
				i += in.readVarUint();
				if (i >= outputDataLongArray.length)
					return;

				size = in.readVarUint();
			}

			LongArrayList array = outputDataLongArray[i];
			array.size(size);

			int light = 0;
			int min_y = in.readVarUint();
			for (int arrayIndex = 0; arrayIndex < array.size(); arrayIndex++) {
				long data = 0;

				// read the block state and light flag
				int id_index = in.readVarUint();
				data = FullDataPointUtil.setId(data, ids[id_index >> 2]);

				// read height and bottom y
				int height;
				if ((id_index&1) != 0)
					height = in.readVarUint();
				else
					height = 1;

				data = FullDataPointUtil.setHeight(data, height);
				data = FullDataPointUtil.setBottomY(data, min_y -= height);

				// read light
				// need to keep track of it since we encode the delta
				if ((id_index & (1<<1)) > 0)
					light ^= in.read();
				data = FullDataPointUtil.setBlockLight(data, light);
				data = FullDataPointUtil.setSkyLight(data, light >> 4);

				// done!
				if (VALIDATE_INPUT_DATAPOINTS) FullDataPointUtil.validateDatapoint(data);
				array.set(arrayIndex, data);
			}
		}
	}

	
	private static void writeGenerationStepsToBlob(ByteArrayList inputColumnGenStepByteArray, ByteArrayList outputByteArray) throws IOException
	{
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		DataOutputStream outputStream = new DataOutputStream(byteArrayOutputStream);

		for (int i = 0; i < inputColumnGenStepByteArray.size(); i++) {
			outputStream.writeByte(inputColumnGenStepByteArray.getByte(i));
		}

		outputStream.flush();
		byteArrayOutputStream.close();
		outputByteArray.addElements(0, byteArrayOutputStream.toByteArray());
	}

	private static void readBlobToGenerationSteps(ByteArrayList inputCompressedDataByteArray, ByteArrayList outputByteArray) throws IOException, DataCorruptedException {
		ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(inputCompressedDataByteArray.elements());
		DataInputStream inputStream = new DataInputStream(byteArrayInputStream);

		try {
			inputStream.readFully(outputByteArray.elements(), 0, FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH);
		} catch (EOFException e) {
			throw new DataCorruptedException(e);
		}
	}


	private static void writeWorldCompressionModeToBlob(ByteArrayList inputWorldCompressionModeByteArray, ByteArrayList outputByteArray) throws IOException {
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		DataOutputStream outputStream = new DataOutputStream(byteArrayOutputStream);

		for (int i = 0; i < inputWorldCompressionModeByteArray.size(); i++) {
			outputStream.write(inputWorldCompressionModeByteArray.getByte(i));
		}

		outputStream.flush();
		byteArrayOutputStream.close();
		outputByteArray.addElements(0, byteArrayOutputStream.toByteArray());
	}

	private static void readBlobToWorldCompressionMode(ByteArrayList inputCompressedDataByteArray, ByteArrayList outputByteArray) throws IOException, DataCorruptedException {
		ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(inputCompressedDataByteArray.elements());
		DataInputStream inputStream = new DataInputStream(byteArrayInputStream);

		try {
			inputStream.readFully(outputByteArray.elements(), 0, FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH);
		} catch (EOFException e) {
			throw new DataCorruptedException(e);
		}
	}


	private static void writeDataMappingToBlob(FullDataPointIdMap mapping, ByteArrayList outputByteArray) throws IOException {
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		DataOutputStream outputStream = new DataOutputStream(byteArrayOutputStream);

		mapping.serialize(outputStream);

		outputStream.flush();
		byteArrayOutputStream.close();
		outputByteArray.addElements(0, byteArrayOutputStream.toByteArray());
	}

	private static FullDataPointIdMap readBlobToDataMapping(ByteArrayList compressedMappingByteArray, long pos, @NotNull ILevelWrapper levelWrapper) throws IOException, InterruptedException, DataCorruptedException {
		ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(compressedMappingByteArray.elements());
		DataInputStream inputStream = new DataInputStream(byteArrayInputStream);

		return FullDataPointIdMap.deserialize(inputStream, pos, levelWrapper);
	}

	//============//
	// networking //
	//============//

	@Override
	public void encode(ByteBuf out) {
		out.writeLong(this.pos);
		out.writeInt(this.dataChecksum);

		EDhApiDataCompressionMode compressionMode = Config.Common.LodBuilding.dataCompression.get();

		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (OutputStream outputStream = new DhDataOutputStream(buffer, compressionMode)) {
			outputStream.write(this.dataByteArray.toByteArray());
		} catch (IOException e) {
            throw new RuntimeException(e);
        }
		out.writeInt(buffer.size());
		out.writeBytes(buffer.toByteArray());

		buffer.reset();
		try (OutputStream outputStream = new DhDataOutputStream(buffer, compressionMode)) {
			outputStream.write(this.columnGenStepByteArray.toByteArray());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		out.writeInt(buffer.size());
		out.writeBytes(buffer.toByteArray());

		buffer.reset();
		try (OutputStream outputStream = new DhDataOutputStream(buffer, compressionMode)) {
			outputStream.write(this.worldCompressionModeByteArray.toByteArray());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		out.writeInt(buffer.size());
		out.writeBytes(buffer.toByteArray());

		buffer.reset();
		try (OutputStream outputStream = new DhDataOutputStream(buffer, compressionMode)) {
			outputStream.write(this.mappingByteArray.toByteArray());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		out.writeInt(buffer.size());
		out.writeBytes(buffer.toByteArray());

		out.writeByte(this.dataFormatVersion);
		out.writeByte(compressionMode.value);
		
		out.writeBoolean(BoolUtil.falseIfNull(this.applyToParent));
		out.writeBoolean(BoolUtil.falseIfNull(this.applyToChildren));
		
		out.writeLong(this.lastModifiedUnixDateTime);
		out.writeLong(this.createdUnixDateTime);
	}

	@Override
	public void decode(ByteBuf in) {
		this.pos = in.readLong();
		this.dataChecksum = in.readInt();

		byte[] compressedData = new byte[in.readInt()];
		in.readBytes(compressedData);

		byte[] compressedColumnGenStep = new byte[in.readInt()];
		in.readBytes(compressedColumnGenStep);

		byte[] compressedWorldCompressionMode = new byte[in.readInt()];
		in.readBytes(compressedWorldCompressionMode);

		byte[] compressedMapping = new byte[in.readInt()];
		in.readBytes(compressedMapping);

		this.dataFormatVersion = in.readByte();

		EDhApiDataCompressionMode compressionMode = EDhApiDataCompressionMode.getFromValue(in.readByte());

		try {
			transferTo(new DhDataInputStream(new ByteArrayInputStream(compressedData), compressionMode), this.dataByteArray);
			transferTo(new DhDataInputStream(new ByteArrayInputStream(compressedColumnGenStep), compressionMode), this.columnGenStepByteArray);
			transferTo(new DhDataInputStream(new ByteArrayInputStream(compressedWorldCompressionMode), compressionMode), this.worldCompressionModeByteArray);
			transferTo(new DhDataInputStream(new ByteArrayInputStream(compressedMapping), compressionMode), this.mappingByteArray);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		this.applyToParent = in.readBoolean();

		this.applyToChildren = in.readBoolean();
		
		this.lastModifiedUnixDateTime = in.readLong();
		this.createdUnixDateTime = in.readLong();
	}
	
	
	
	//================//
	// helper methods //
	//================//

	// getOrderedUniques mutates the array and returns the number of unique elements,
	// which is also the size of the portion of the array that it sorts.
	// this portion contains no duplicates, and is ordered from most-duplicated to least-duplicated.
	//
	// e.g. [1,3,5,2,2,2,7,3,5,3,2] -> [2,3,5,1,7...] (returning 5)
	private static int getOrderedUniques(int[] values) {
		if (values.length <= 2) return values.length;

		int uc = 0, dc = values.length - 1, tmp;
		int[] counts = new int[values.length];

		// general idea here is have a unique and duplicate counter/index.
		// unique counter starts at the beginning of the array and duplicate counter starts at the end.
		// when a unique element is found we increment the unique counter,
		// when a duplicate element is found we move it to the end of the array
		// and make sure its partner inside the unique portion of the array is ordered.

		outer:
		while (uc <= dc) {
			for (int i = 0; i < uc; i++)
				if (values[i] == values[uc]) {
					// this value already exists in our unique section.
					// swap with duplicate cursor and increment duplicate cursor.
					tmp = values[dc];
					values[dc] = values[uc];
					values[uc] = tmp;
					dc--;

					// and maintain order
					counts[i]++;
					while (i > 0 && counts[i - 1] < counts[i]) {
						tmp = values[i];
						values[i] = values[i - 1];
						values[i - 1] = tmp;

						tmp = counts[i];
						counts[i] = counts[i - 1];
						counts[i - 1] = tmp;
						i--;
					}
					continue outer;
				}
			uc++;
		}

		return uc;
	}
	
	//===========//
	// overrides //
	//===========//

	@Override
	public Long getKey() {
		return this.pos;
	}

	@Override
	public String getKeyDisplayString() {
		return DhSectionPos.toString(this.pos);
	}

	@Override
	public String toString() {
		return MoreObjects.toStringHelper(this)
				.add("levelMinY", this.levelMinY)
				.add("pos", this.pos)
				.add("dataChecksum", this.dataChecksum)
				.add("dataByteArray length", this.dataByteArray.size())
				.add("columnGenStepByteArray length", this.columnGenStepByteArray.size())
				.add("worldCompressionModeByteArray length", this.worldCompressionModeByteArray.size())
				.add("mappingByteArray length", this.mappingByteArray.size())
				.add("dataFormatVersion", this.dataFormatVersion)
				.add("applyToParent", this.applyToParent)
				.add("applyToChildren", this.applyToChildren)
				.add("lastModifiedUnixDateTime", this.lastModifiedUnixDateTime)
				.add("createdUnixDateTime", this.createdUnixDateTime)
				.toString();
	}


	@Deprecated // copied straight out of InputStream because it's only java 9+ (we're java 8)
	public long transferTo(InputStream in, ByteArrayList out) throws IOException {
		try {
			long transferred = 0L;
			byte[] buffer = new byte[16384];

			int read;
			while ((read = in.read(buffer, 0, 16384)) >= 0) {
				out.addElements((int) transferred, buffer, 0, read);
				if (transferred < Long.MAX_VALUE) {
					try {
						transferred = Math.addExact(transferred, read);
					} catch (ArithmeticException var7) {
						transferred = Long.MAX_VALUE;
					}
				}
			}

			return transferred;
		} finally {
			in.close();
		}
	}
}
