package com.seibel.distanthorizons.core.util.objects.dataStreams;

import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;

public class ByteArrayListOutputStream extends OutputStream
{
	private final ByteArrayList byteArray;
	
	public ByteArrayListOutputStream(ByteArrayList byteArray)
	{
		this.byteArray = byteArray;
	}
	
	@Override
	public void write(int i) throws IOException
	{
		byteArray.add((byte) i);
	}
	
}
