package com.seibel.distanthorizons.core.multiplayer.fullData;

import com.google.common.base.MoreObjects;
import com.seibel.distanthorizons.api.enums.config.EDhApiDataCompressionMode;
import com.seibel.distanthorizons.api.interfaces.override.datastore.IDhApiDatastore;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.network.INetworkObject;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSplitMessage;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.sql.dto.BeaconBeamDTO;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * @see FullDataSplitMessage
 */
public class FullDataPayload implements INetworkObject, AutoCloseable
{
	private static final AtomicInteger lastBufferId = new AtomicInteger();
	
	public int dtoBufferId;
	public ByteBuf dtoBuffer;
	
	public List<IDhApiDatastore.BeaconBeam> beaconBeams;
	
	
	
	//==============//
	// constructors //
	//==============//
	
	public FullDataPayload() { }
	public FullDataPayload(@NotNull FullDataSourceV2 fullDataSource, List<IDhApiDatastore.BeaconBeam> beaconBeams)
	{
		Objects.requireNonNull(fullDataSource);
		
		this.dtoBufferId = lastBufferId.getAndIncrement();
		
		try
		{
			EDhApiDataCompressionMode compressionMode = Config.Common.LodBuilding.dataCompression.get();
			try (FullDataSourceV2DTO dataSourceDto = FullDataSourceV2DTO.CreateFromDataSource(fullDataSource, compressionMode))
			{
				this.dtoBuffer = ByteBufAllocator.DEFAULT.buffer();
				dataSourceDto.encode(this.dtoBuffer);
			}
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
		
		this.beaconBeams = beaconBeams;
	}
	
	
	
	//===============//
	// serialization //
	//===============//
	
	@Override
	public void encode(ByteBuf out)
	{
		out.writeInt(this.dtoBufferId);
		this.writeCollection(out, this.beaconBeams.stream().map(beam -> new BeaconBeamDTO(
				new DhBlockPos(beam.blockPosX, beam.blockPosY, beam.blockPosZ),
				new Color(beam.colourR, beam.colourG, beam.colourB)
		)).collect(Collectors.toList()));
	}
	
	@Override
	public void decode(ByteBuf in)
	{
		this.dtoBufferId = in.readInt();
		this.beaconBeams = this.readCollection(in, new ArrayList<>(), () -> new BeaconBeamDTO(null, null))
				.stream().map(dto -> {
					IDhApiDatastore.BeaconBeam beam = new IDhApiDatastore.BeaconBeam();
					beam.blockPosX = dto.blockPos.getX();
					beam.blockPosY = dto.blockPos.getY();
					beam.blockPosZ = dto.blockPos.getZ();
					beam.colourR = dto.color.getRed();
					beam.colourG = dto.color.getGreen();
					beam.colourB = dto.color.getBlue();
					return beam;
				}).collect(Collectors.toList());
	}
	
	
	
	//================//
	// base overrides //
	//================//
	
	@Override
	public void close()
	{
		this.dtoBuffer.release();
	}
	
	@Override
	public String toString()
	{
		return MoreObjects.toStringHelper(this)
				.add("dtoBufferId", this.dtoBufferId)
				.add("dtoBuffer", this.dtoBuffer)
				.add("beaconBeams", this.beaconBeams)
				.toString();
	}
	
}
