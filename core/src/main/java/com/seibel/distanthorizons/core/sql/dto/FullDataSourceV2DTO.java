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
import com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.network.INetworkObject;
import com.seibel.distanthorizons.core.pooling.PhantomArrayListParent;
import com.seibel.distanthorizons.core.pooling.PhantomArrayListPool;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import com.seibel.distanthorizons.core.util.ListUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.objects.DataCorruptedException;
import com.seibel.distanthorizons.core.util.objects.dataStreams.ByteArrayListOutputStream;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataInputStream;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.jetbrains.annotations.NotNull;

import java.io.*;

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

	public boolean applyToParent;

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

		this.pooledArraysCheckout = null;
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

		return dataSource;
	}


	//=================//
	// (de)serializing //
	//=================//

	private static void writeDataSourceDataArrayToBlob(LongArrayList[] inputDataArray, ByteArrayList outputByteArray) throws IOException {
		// write the outputs to a stream to prep for writing to the database
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		DataOutputStream outputStream = new DataOutputStream(byteArrayOutputStream);

		// write the data
		int dataArrayLength = FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH;
		for (int xz = 0; xz < dataArrayLength; xz++) {
			LongArrayList dataColumn = inputDataArray[xz];

			// write column length
			short columnLength = (dataColumn != null) ? (short) dataColumn.size() : 0;
			// a short is used instead of an int because at most we store 4096 vertical slices and a
			// short fits that with less wasted spaces vs an int (short has max value of 32,767 vs int's max of 2 billion)
			outputStream.writeShort(columnLength);

			// write column data (will be skipped if no data was present)
			for (int y = 0; y < columnLength; y++) {
				outputStream.writeLong(dataColumn.getLong(y));
			}
		}


		// generate the checksum
		outputStream.flush();
		byteArrayOutputStream.close();
		outputByteArray.addElements(0, byteArrayOutputStream.toByteArray());
	}

	private static void readBlobToDataSourceDataArray(ByteArrayList inputCompressedDataByteArray, LongArrayList[] outputDataLongArray) throws IOException, DataCorruptedException {
		ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(inputCompressedDataByteArray.elements());
		DataInputStream inputStream = new DataInputStream(byteArrayInputStream);


		// read the data
		int dataArrayLength = FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH;
		for (int xz = 0; xz < dataArrayLength; xz++) {
			// read the column length
			short dataColumnLength = inputStream.readShort(); // separate variables are used for debugging and in case validation wants to be added later
			if (dataColumnLength < 0) {
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


	private static void writeGenerationStepsToBlob(ByteArrayList inputColumnGenStepByteArray, ByteArrayList outputByteArray) throws IOException {
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

		out.writeInt(this.dataByteArray.size());
		out.writeBytes(this.dataByteArray.toByteArray());

		out.writeInt(this.columnGenStepByteArray.size());
		out.writeBytes(this.columnGenStepByteArray.toByteArray());

		out.writeInt(this.worldCompressionModeByteArray.size());
		out.writeBytes(this.columnGenStepByteArray.toByteArray());

		out.writeInt(this.mappingByteArray.size());
		out.writeBytes(this.mappingByteArray.toByteArray());

		out.writeByte(this.dataFormatVersion);
		out.writeByte(EDhApiDataCompressionMode.UNCOMPRESSED.value);

		out.writeBoolean(this.applyToParent);

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
			transferTo(new DhDataInputStream(new ByteArrayInputStream(compressedData), compressionMode), new ByteArrayListOutputStream(this.dataByteArray));
			transferTo(new DhDataInputStream(new ByteArrayInputStream(compressedColumnGenStep), compressionMode), new ByteArrayListOutputStream(this.columnGenStepByteArray));
			transferTo(new DhDataInputStream(new ByteArrayInputStream(compressedWorldCompressionMode), compressionMode), new ByteArrayListOutputStream(this.worldCompressionModeByteArray));
			transferTo(new DhDataInputStream(new ByteArrayInputStream(compressedMapping), compressionMode), new ByteArrayListOutputStream(this.mappingByteArray));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		this.applyToParent = in.readBoolean();

		this.lastModifiedUnixDateTime = in.readLong();
		this.createdUnixDateTime = in.readLong();
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
				.add("lastModifiedUnixDateTime", this.lastModifiedUnixDateTime)
				.add("createdUnixDateTime", this.createdUnixDateTime)
				.toString();
	}


	@Deprecated // copied straight out of InputStream because it's only java 9+ (we're java 8)
	public long transferTo(InputStream in, OutputStream out) throws IOException {
		try {
			long transferred = 0L;
			byte[] buffer = new byte[16384];

			int read;
			while ((read = in.read(buffer, 0, 16384)) >= 0) {
				out.write(buffer, 0, read);
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
			out.close();
		}
	}
}
