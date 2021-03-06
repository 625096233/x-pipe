package com.ctrip.xpipe.redis.keeper.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.ctrip.xpipe.endpoint.DefaultEndPoint;
import com.ctrip.xpipe.lifecycle.LifecycleHelper;
import com.ctrip.xpipe.redis.core.protocal.protocal.LenEofType;
import com.ctrip.xpipe.redis.core.store.MetaStore;
import com.ctrip.xpipe.redis.core.store.ReplicationStore;
import com.ctrip.xpipe.redis.keeper.AbstractRedisKeeperTest;
import com.ctrip.xpipe.redis.keeper.config.TestKeeperConfig;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * @author marsqing
 *
 *         Jun 1, 2016 9:47:12 AM
 */
public class DefaultReplicationStoreManagerTest extends AbstractRedisKeeperTest {
	
	private int replicationStoreGcIntervalSeconds = 1;
	
	private int minTimeMilliToGcAfterCreate = 3000;
	
	private TestKeeperConfig keeperConfig;
	
	@Before
	public void beforeDefaultReplicationStoreManagerTest(){
		
		keeperConfig = new TestKeeperConfig();
		keeperConfig.setReplicationStoreGcIntervalSeconds(replicationStoreGcIntervalSeconds);
		keeperConfig.setMinTimeMilliToGcAfterCreate(minTimeMilliToGcAfterCreate);
	}
	


	@Test
	public void testMultiManagerGc() throws InterruptedException, IOException {

		final DefaultReplicationStoreManager replicationStoreManager1 = (DefaultReplicationStoreManager) createReplicationStoreManager(
				keeperConfig);
		final DefaultReplicationStoreManager replicationStoreManager2 = (DefaultReplicationStoreManager) createReplicationStoreManager(
				keeperConfig);
		final AtomicReference<DefaultReplicationStore> store = new AtomicReference<DefaultReplicationStore>(null);

		for(int i = 0; i < 10; i++){
			
			logger.info("[testMultiManagerGc]{}", i);
			
			final CountDownLatch latch = new CountDownLatch(2);
			
			executors.execute(new Runnable() {
				
				@Override
				public void run() {
					
					try {
						store.set((DefaultReplicationStore) replicationStoreManager1.create());;
					} catch (IOException e) {
						logger.error("[run]" + replicationStoreManager1, e);
					}finally{
						latch.countDown();
					}
				}
			});
			executors.execute(new Runnable() {
				
				@Override
				public void run() {

					try {
						replicationStoreManager2.gc();
					} catch (IOException e) {
						logger.error("[run]" + replicationStoreManager2, e);
					}finally{
						latch.countDown();
					}
				}
			});
			
			latch.await();
			Assert.assertNotNull(store.get());
			Assert.assertTrue(store.get().getBaseDir().exists());
		}
		
		sleep(minTimeMilliToGcAfterCreate + 1000);
		logger.info("[testMultiManagerGc][lastgc]");
		replicationStoreManager1.gc();
		File baseDir = replicationStoreManager1.getBaseDir();
		File []files = baseDir.listFiles();
		Assert.assertEquals(2, files.length);
	}
	
	
	@Test
	public void testCancelGc() throws Exception {

		DefaultReplicationStoreManager replicationStoreManager = (DefaultReplicationStoreManager) createReplicationStoreManager(
				keeperConfig);
		
		LifecycleHelper.initializeIfPossible(replicationStoreManager);
		LifecycleHelper.startIfPossible(replicationStoreManager);
		
		sleep(replicationStoreGcIntervalSeconds * 2000);
		

		LifecycleHelper.stopIfPossible(replicationStoreManager);
		LifecycleHelper.disposeIfPossible(replicationStoreManager);
		
		long gcCount = replicationStoreManager.getGcCount();
		Assert.assertTrue(gcCount > 0);

		sleep(replicationStoreGcIntervalSeconds * 2000);

		Assert.assertEquals(gcCount, replicationStoreManager.getGcCount());
	}

	
	@Test
	public void testDestroy() throws Exception{
		
		DefaultReplicationStoreManager replicationStoreManager = (DefaultReplicationStoreManager) createReplicationStoreManager(
				keeperConfig);
		
		LifecycleHelper.initializeIfPossible(replicationStoreManager);
		LifecycleHelper.startIfPossible(replicationStoreManager);
		
		DefaultReplicationStore store = (DefaultReplicationStore) replicationStoreManager.create();
		
		Assert.assertTrue(store.getBaseDir().exists());
		
		replicationStoreManager.destroy();
		
		Assert.assertTrue(!store.getBaseDir().exists());
		
	}
	
	
	@Test
	public void testConcurrentGc() throws IOException, InterruptedException {

		final DefaultReplicationStoreManager mgr = (DefaultReplicationStoreManager) createReplicationStoreManager();
		for (int i = 0; i < 10; i++) {

			logger.info("[testGc]{}", i);

			final CountDownLatch latch = new CountDownLatch(2);
			final AtomicReference<DefaultReplicationStore> store = new AtomicReference<DefaultReplicationStore>(null);

			executors.execute(new Runnable() {

				@Override
				public void run() {
					try {
						store.set((DefaultReplicationStore) mgr.create());
					} catch (IOException e) {
						logger.error("[testGc]", e);
					} finally {
						latch.countDown();
					}
				}
			});

			executors.execute(new Runnable() {

				@Override
				public void run() {

					try {
						mgr.gc();
					} catch (IOException e) {
						logger.error("[testGc][gc]", e);
					} finally {
						latch.countDown();
					}

				}
			});

			latch.await();
			Assert.assertNotNull(store.get());
			Assert.assertTrue(store.get().getBaseDir().exists());
		}
	}

	@Test
	public void test() throws Exception {
		
		String keeperRunid = randomKeeperRunid();
		
		File baseDir = new File(getTestFileDir());
		String clusterId = "cluster1";
		String shardId = "shard1";
		DefaultReplicationStoreManager mgr = (DefaultReplicationStoreManager) createReplicationStoreManager(clusterId,
				shardId, keeperRunid, baseDir);

		ReplicationStore currentStore = mgr.getCurrent();
		assertNull(currentStore);

		currentStore = mgr.create();

		assertEquals(clusterId, mgr.getClusterName());
		assertEquals(shardId, mgr.getShardName());
		assertEquals(currentStore, mgr.getCurrent());

		DefaultReplicationStore newCurrentStore = (DefaultReplicationStore) mgr.create();
		assertEquals(newCurrentStore, mgr.getCurrent());
		assertNotEquals(currentStore, mgr.getCurrent());

		MetaStore metaStore = newCurrentStore.getMetaStore();
		metaStore.setMasterAddress(new DefaultEndPoint("redis://127.0.0.1:6379"));
		newCurrentStore.beginRdb("masterRunid", 0, new LenEofType(100));

		ByteBuf cmdBuf = Unpooled.buffer();
		cmdBuf.writeByte(9);
		newCurrentStore.getCommandStore().appendCommands(cmdBuf);

		DefaultReplicationStoreManager mgr2 = (DefaultReplicationStoreManager) createReplicationStoreManager(clusterId,shardId, keeperRunid, baseDir);
		
		assertEquals(metaStore.getMasterRunid(), mgr2.getCurrent().getMetaStore().getMasterRunid());
		assertEquals(metaStore.getKeeperBeginOffset(), mgr2.getCurrent().getMetaStore().getKeeperBeginOffset());
		assertEquals(metaStore.getMasterAddress(), mgr2.getCurrent().getMetaStore().getMasterAddress());
		assertEquals(metaStore.beginOffset(), mgr2.getCurrent().getMetaStore().beginOffset());
	}

}
