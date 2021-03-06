package com.ctrip.xpipe.payload;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.CountDownLatch;

import org.junit.Assert;
import org.junit.Test;

import com.ctrip.xpipe.AbstractTest;
import com.ctrip.xpipe.concurrent.AbstractExceptionLogTask;
import com.ctrip.xpipe.testutils.MemoryPrinter;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;

/**
 * @author wenchao.meng
 *
 * 2016年4月24日 下午8:58:12
 */
public class ByteArrayOutputStreamPayloadTest extends AbstractTest{
	
	
	@Test
	public void testInout() throws IOException{
		
		ByteArrayOutputStreamPayload payload = new ByteArrayOutputStreamPayload();
		String randomStr = randomString();
		
		ByteBuf byteBuf = ByteBufAllocator.DEFAULT.buffer(randomStr.length());
		byteBuf.writeBytes(randomStr.getBytes());
		payload.startInput();
		payload.in(byteBuf);
		payload.endInput();
		
		
		final ByteBuf result = ByteBufAllocator.DEFAULT.buffer(randomStr.length());
		payload.startOutput();
		long wroteLength = payload.out(new WritableByteChannel() {
			
			@Override
			public boolean isOpen() {
				return false;
			}
			
			@Override
			public void close() throws IOException {
				
			}
			
			@Override
			public int write(ByteBuffer src) throws IOException {
				
				int readable = result.readableBytes();
				result.writeBytes(src);
				return result.readableBytes() - readable;
			}
		});
		payload.endOutput();

		
		Assert.assertEquals(randomStr.length(), wroteLength);
		
		byte []resultArray = new byte[(int) wroteLength];
		result.readBytes(resultArray);
		Assert.assertEquals(randomStr, new String(resultArray));
	}
	
	
	@Test
	public void testNewHeap() throws IOException, InterruptedException{
		
		final MemoryPrinter memoryPrinter = new MemoryPrinter();

		memoryPrinter.printMemory();

		final int length = 1 << 10;
		int concurrentCount = 10;
		final CountDownLatch latch = new CountDownLatch(concurrentCount);
		
		final ByteBuf byteBuf = PooledByteBufAllocator.DEFAULT.directBuffer(length);
		byteBuf.writeBytes(randomString(length).getBytes());

		byte []dst = new byte[length];
		byteBuf.readBytes(dst);

		memoryPrinter.printMemory();

		for(int i=0;i<concurrentCount;i++){

			Thread current = new Thread(
					new AbstractExceptionLogTask() {
						@Override
						protected void doRun() throws Exception {
							
							try{
								byteBuf.readerIndex(0);
								ByteArrayOutputStream baous = new ByteArrayOutputStream();
								byteBuf.readBytes(baous, length);
							}finally{
								latch.countDown();
							}
						}
			});
			current.start();
			memoryPrinter.printMemory();
			
		}
		
		latch.await();
	}

}
