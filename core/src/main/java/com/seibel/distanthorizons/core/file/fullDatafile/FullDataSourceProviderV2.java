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

package com.seibel.distanthorizons.core.file.fullDatafile;

import com.seibel.distanthorizons.api.interfaces.override.datastore.IDhApiDatastore;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV1;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.file.structure.ISaveStructure;
import com.seibel.distanthorizons.core.generation.tasks.WorldGenResult;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.render.renderer.DebugRenderer;
import com.seibel.distanthorizons.core.render.renderer.IDebugRenderable;
import com.seibel.distanthorizons.core.sql.SQLiteDatastore;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.ThreadUtil;
import com.seibel.distanthorizons.core.util.objects.DataCorruptedException;
import com.seibel.distanthorizons.core.util.threading.PositionalLockProvider;
import com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.coreapi.DependencyInjection.OverrideInjector;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Handles reading/writing {@link FullDataSourceV2}
 * to and from the database.
 */
public class FullDataSourceProviderV2
		implements IDebugRenderable {
	private static final Logger LOGGER = DhLoggerBuilder.getLogger();
	private static final IMinecraftClientWrapper MC_CLIENT = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	
	protected static final int NUMBER_OF_PARENT_UPDATE_TASKS_PER_THREAD = 5;
	/** how many parent update tasks can be in the queue at once */
	protected static int getMaxUpdateTaskCount() { return NUMBER_OF_PARENT_UPDATE_TASKS_PER_THREAD* Config.Common.MultiThreading.numberOfThreads.get(); } 
	
	/** indicates how long the update queue thread should wait between queuing ticks */
	protected static final int UPDATE_QUEUE_THREAD_DELAY_IN_MS = 250;

	/**
	 * how many data sources should be pulled down for migration at once
	 */
	private static final int MIGRATION_BATCH_COUNT = NUMBER_OF_PARENT_UPDATE_TASKS_PER_THREAD;
	/**
	 * 5 minutes <br>
	 * This should be much longer than any update should take. This is just
	 * to make sure the thread doesn't get stuck.
	 */
	private static final int MIGRATION_MAX_UPDATE_TIMEOUT_IN_MS = 5 * 60 * 1_000;

	private static final Set<String> CORRUPT_DATA_ERRORS_LOGGED = Collections.newSetFromMap(new ConcurrentHashMap<>());

	/**
	 * The highest numerical detail level possible.
	 * Used when determining which positions to update.
	 *
	 * @see FullDataSourceProviderV2#MIN_SECTION_DETAIL_LEVEL
	 */
	public static final byte TOP_SECTION_DETAIL_LEVEL = DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL + LodUtil.REGION_DETAIL_LEVEL;
	/**
	 * The lowest numerical detail level possible.
	 *
	 * @see FullDataSourceProviderV2#TOP_SECTION_DETAIL_LEVEL
	 */
	public static final byte MIN_SECTION_DETAIL_LEVEL = DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;

	/**
	 * Interrupting the migration thread pool doesn't work well and may corrupt the database
	 * vs gracefully shutting down the thread ourselves.
	 */
	protected final AtomicBoolean migrationThreadRunning = new AtomicBoolean(true);
	protected final FullDataSourceProviderV1<IDhLevel> legacyFileHandler;

	protected boolean migrationStartMessageQueued = false;

	protected long legacyDeletionCount = -1;
	protected long migrationCount = -1;
	protected boolean migrationStoppedWithError = false;

	/**
	 * Tracks which positions are currently being updated
	 * to prevent duplicate concurrent updates.
	 */
	public final Set<Long> updatingPosSet = ConcurrentHashMap.newKeySet();

	// TODO only run thread if modifications happened recently
	/**
	 * Will be null on the dedicated server since updates don't need to be propagated,
	 * only the highest detail level is needed.
	 */
	@Nullable
	private final ThreadPoolExecutor updateQueueProcessor;

	protected final PositionalLockProvider updateLockProvider = new PositionalLockProvider();
	/**
	 * generally just used for debugging,
	 * keeps track of which positions are currently locked.
	 */
	public final Set<Long> lockedPosSet = ConcurrentHashMap.newKeySet();
	public final ConcurrentHashMap<Long, AtomicInteger> queuedUpdateCountsByPos = new ConcurrentHashMap<>();

	protected volatile boolean isShutdown = false;
	protected final ReentrantLock closeLock = new ReentrantLock();

	protected final IDhLevel level;
	protected final File saveDir;

	public IDhApiDatastore datastore;
	public int levelId;

	public final ArrayList<Consumer<FullDataSourceV2>> dateSourceUpdateListeners = new ArrayList<>();


	//=============//
	// constructor //
	//=============//

	public FullDataSourceProviderV2(IDhLevel level, ISaveStructure saveStructure, @Nullable File saveDirOverride) {
		this.datastore = OverrideInjector.INSTANCE.get(IDhApiDatastore.class);
		if (this.datastore == null) {
			this.datastore = new SQLiteDatastore(Config.Common.LodBuilding.dataCompression.get());
			OverrideInjector.INSTANCE.bind(IDhApiDatastore.class, this.datastore);
		}

		this.level = level;
		this.saveDir = (saveDirOverride == null) ? saveStructure.getSaveFolder(level.getLevelWrapper()) : saveDirOverride;
		this.levelId = this.datastore.ensureLevel(new File(this.saveDir.getPath() + File.separator + ISaveStructure.DATABASE_NAME).getAbsolutePath());

		this.legacyFileHandler = new FullDataSourceProviderV1<>(level, saveStructure, saveDirOverride);

		DebugRenderer.register(this, Config.Client.Advanced.Debugging.DebugWireframe.showFullDataUpdateStatus);

		String levelId = level.getLevelWrapper().getDhIdentifier();

		// start migrating any legacy data sources present in the background
		ThreadPoolExecutor executor = ThreadPoolUtil.getFullDataMigrationExecutor();
		if (executor != null) {
			executor.execute(this::convertLegacyDataSources);
		} else {
			// shouldn't happen, but just in case
			LOGGER.error("Unable to start migration for level: [" + levelId + "] due to missing executor.");
		}

		// update propagation doesn't need to be run on the server since only the highest detail level is needed
		this.updateQueueProcessor = ThreadUtil.makeSingleThreadPool("Parent Update Queue [" + levelId + "]");
		this.updateQueueProcessor.execute(this::runUpdateQueue);
	}

	protected FullDataSourceV2DTO createDtoFromDataSource(FullDataSourceV2 dataSource) {
		try {
			return FullDataSourceV2DTO.CreateFromDataSource(dataSource);
		} catch (IOException e) {
			LOGGER.warn("Unable to create DTO, error: " + e.getMessage(), e);
			return null;
		}
	}

	protected FullDataSourceV2 createDataSourceFromDto(FullDataSourceV2DTO dto) throws InterruptedException, IOException, DataCorruptedException {
		return dto.createDataSource(this.level.getLevelWrapper());
	}

	protected FullDataSourceV2 makeEmptyDataSource(long pos) {
		return FullDataSourceV2.createEmpty(pos);
	}


	//================//
	// parent updates //
	//================//

	private void runUpdateQueue() {
		while (!Thread.interrupted()) {
			try {
				Thread.sleep(UPDATE_QUEUE_THREAD_DELAY_IN_MS);

				PriorityTaskPicker.Executor executor = ThreadPoolUtil.getUpdatePropagatorExecutor();
				if (executor == null || executor.isTerminated()) {
					continue;
				}

				// TODO it might be worth skipping this logic if no parent updates happened

				// update positions closest to the player (if not on a server)
				// to make world gen appear faster
				DhBlockPos targetBlockPos = DhBlockPos.ZERO;
				if (MC_CLIENT != null && MC_CLIENT.playerExists()) {
					targetBlockPos = MC_CLIENT.getPlayerBlockPos();
				}

				this.runParentUpdates(executor, targetBlockPos);
				this.runChildUpdates(executor, targetBlockPos);

			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				LOGGER.error("Unexpected error in the parent update queue thread. Error: " + e.getMessage(), e);
			}
		}

		LOGGER.info("Update thread [" + Thread.currentThread().getName() + "] terminated.");
	}

	/**
	 * will always apply updates
	 */
	private void runParentUpdates(PriorityTaskPicker.Executor executor, DhBlockPos targetBlockPos) {
		int maxUpdateTaskCount = getMaxUpdateTaskCount();

		// queue parent updates
		if (executor.getQueueSize() < maxUpdateTaskCount
			&& this.updatingPosSet.size() < maxUpdateTaskCount) {
			// get the positions that need to be applied to their parents
			List<IDhApiDatastore.FullDataPosition> parentUpdatePosList = this.datastore.getFullDataParentPositionsToUpdate(
					this.levelId,
					targetBlockPos.getX(),
					targetBlockPos.getZ(),
					maxUpdateTaskCount
			);

			// combine updates together based on their parent
			HashMap<Long, HashSet<Long>> updatePosByParentPos = new HashMap<>();
			for (IDhApiDatastore.FullDataPosition fullDataPos : parentUpdatePosList) {
				long pos = DhSectionPos.encode(fullDataPos.detailLevel, fullDataPos.posX, fullDataPos.posZ);
				updatePosByParentPos.compute(DhSectionPos.getParentPos(pos), (parentPos, updatePosSet) ->
				{
					if (updatePosSet == null) {
						updatePosSet = new HashSet<>();
					}
					updatePosSet.add(pos);
					return updatePosSet;
				});
			}

			// queue the updates
			for (Long parentUpdatePos : updatePosByParentPos.keySet()) {
				// stop if there are already a bunch of updates queued
				if (this.updatingPosSet.size() > maxUpdateTaskCount
					|| executor.getQueueSize() > maxUpdateTaskCount
					|| !this.updatingPosSet.add(parentUpdatePos)) {
					break;
				}

				try {
					executor.execute(() ->
					{
						ReentrantLock parentWriteLock = this.updateLockProvider.getLock(parentUpdatePos);
						boolean parentLocked = false;
						try {
							//LOGGER.info("updating parent: "+parentUpdatePos);

							// Locking the parent before the children should prevent deadlocks.
							// TryLock is used instead of lock so this thread can handle a different update.
							if (parentWriteLock.tryLock()) {
								parentLocked = true;
								this.lockedPosSet.add(parentUpdatePos);

								try (FullDataSourceV2 parentDataSource = this.get(parentUpdatePos)) {
									// will return null if the file handler is shutting down
									if (parentDataSource != null) {
										// apply each child pos to the parent
										for (Long childPos : updatePosByParentPos.get(parentUpdatePos)) {
											ReentrantLock childReadLock = this.updateLockProvider.getLock(childPos);
											try {
												childReadLock.lock();
												this.lockedPosSet.add(childPos);

												try (FullDataSourceV2 childDataSource = this.get(childPos)) {
													// can return null when the file handler is being shut down
													if (childDataSource != null) {
														parentDataSource.update(childDataSource);
													}
												}
											} catch (Exception e) {
												LOGGER.error("Unexpected in parent update propagation for parent pos: [" + DhSectionPos.toString(parentUpdatePos) + "], child pos: [" + DhSectionPos.toString(parentUpdatePos) + "], Error: [" + e.getMessage() + "].", e);
											} finally {
												childReadLock.unlock();
												this.lockedPosSet.remove(childPos);
											}
										}


										if (DhSectionPos.getDetailLevel(parentUpdatePos) < TOP_SECTION_DETAIL_LEVEL) {
											parentDataSource.applyToParent = true;
										}

										this.updateDataSourceAtPos(parentUpdatePos, parentDataSource, false);
										for (Long childPos : updatePosByParentPos.get(parentUpdatePos)) {
											this.datastore.updateFullDataApplyToParent(
													this.levelId,
													DhSectionPos.getDetailLevel(childPos),
													DhSectionPos.getX(childPos),
													DhSectionPos.getZ(childPos),
													false
											);
										}
									}
								}
							}
						} finally {
							if (parentLocked) {
								parentWriteLock.unlock();
								this.lockedPosSet.remove(parentUpdatePos);
							}

							this.updatingPosSet.remove(parentUpdatePos);
						}
					});
				} catch (
						RejectedExecutionException ignore) { /* the executor was shut down, it should be back up shortly and able to accept new jobs */ } catch (
						Exception e) {
					this.updatingPosSet.remove(parentUpdatePos);
					throw e;
				}
			}
		}
	}

	/**
	 * stops if it finds any LOD data
	 */
	private void runChildUpdates(PriorityTaskPicker.Executor executor, DhBlockPos targetBlockPos) {
		int maxUpdateTaskCount = getMaxUpdateTaskCount();

		// queue child updates
		if (executor.getQueueSize() < maxUpdateTaskCount
			&& this.updatingPosSet.size() < maxUpdateTaskCount) {
			// get the positions that need to be applied to their children
			List<IDhApiDatastore.FullDataPosition> childUpdatePosList = this.datastore.getFullDataChildrenPositionsToUpdate(this.levelId, targetBlockPos.getX(), targetBlockPos.getZ(), maxUpdateTaskCount);

			// queue the updates
			for (IDhApiDatastore.FullDataPosition parentUpdateFullDataPos : childUpdatePosList) {
				final long parentUpdatePos = DhSectionPos.encode(parentUpdateFullDataPos.detailLevel, parentUpdateFullDataPos.posX, parentUpdateFullDataPos.posZ);
				// stop if there are already a bunch of updates queued
 				if (this.updatingPosSet.size() > maxUpdateTaskCount
					    || executor.getQueueSize() > maxUpdateTaskCount)
				{
					break;
				}
				
				// skip already updating positions
				if (!this.updatingPosSet.add(parentUpdatePos))
				{
					continue;
				}
				
				try
				{
					executor.execute(() ->
					{
						ReentrantLock parentReadLock = this.updateLockProvider.getLock(parentUpdatePos);
						boolean parentLocked = false;
						try {
							//LOGGER.info("updating parent: "+parentUpdatePos);

							// Locking the parent before the children should prevent deadlocks.
							// TryLock is used instead of lock so this thread can handle a different update.
							if (parentReadLock.tryLock()) {
								parentLocked = true;
								this.lockedPosSet.add(parentUpdatePos);

								try (FullDataSourceV2 parentDataSource = this.get(parentUpdatePos)) {
									// will return null if the file handler is shutting down
									if (parentDataSource != null) {
										// apply parent to each child
										for (int i = 0; i < 4; i++) {
											long childPos = DhSectionPos.getChildByIndex(parentUpdatePos, i);

											ReentrantLock childWriteLock = this.updateLockProvider.getLock(childPos);
											try {
												childWriteLock.lock();
												this.lockedPosSet.add(childPos);

												try (FullDataSourceV2 childDataSource = this.get(childPos)) {
													// will return null if the file handler is shutting down
													if (childDataSource != null) {
														childDataSource.update(parentDataSource);

														// don't propagate child updates past the bottom of the tree
														if (DhSectionPos.getDetailLevel(childPos) != DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL) {
															childDataSource.applyToChildren = true;
														}

														this.updateDataSourceAtPos(childPos, childDataSource, false);
													}
												}
											} catch (Exception e) {
												LOGGER.error("Unexpected in child update propagation for parent pos: [" + DhSectionPos.toString(parentUpdatePos) + "], child pos: [" + DhSectionPos.toString(parentUpdatePos) + "], Error: [" + e.getMessage() + "].", e);
											} finally {
												childWriteLock.unlock();
												this.lockedPosSet.remove(childPos);
											}
										}

										this.datastore.updateFullDataApplyToChildren(levelId, parentUpdateFullDataPos.detailLevel, parentUpdateFullDataPos.posX, parentUpdateFullDataPos.posZ, false);
									}
								}
							}
						} finally {
							if (parentLocked) {
								parentReadLock.unlock();
								this.lockedPosSet.remove(parentUpdatePos);
							}

							this.updatingPosSet.remove(parentUpdatePos);
						}
					});
				} catch (
						RejectedExecutionException ignore) { /* the executor was shut down, it should be back up shortly and able to accept new jobs */ } catch (
						Exception e) {
					this.updatingPosSet.remove(parentUpdatePos);
					throw e;
				}
			}
		}
	}

	/**
	 * After this method returns the inputData will be written to file.
	 *
	 * @param updatePos the position to update
	 */
	protected void updateDataSourceAtPos(long updatePos, @NotNull FullDataSourceV2 inputData, boolean lockOnUpdatePos) {
		boolean methodLocked = false;
		// a lock is necessary to prevent two threads from writing to the same position at once,
		// if that happens only the second update will apply and the LOD will end up with hole(s)
		ReentrantLock updateLock = this.updateLockProvider.getLock(updatePos);

		try {
			if (lockOnUpdatePos) {
				methodLocked = true;
				updateLock.lock();
				this.lockedPosSet.add(updatePos);
			}


			// get or create the data source
			try (FullDataSourceV2 recipientDataSource = this.get(updatePos)) {
				if (recipientDataSource != null) {
					boolean dataModified = recipientDataSource.update(inputData, this.level);
					if (dataModified) {
						// save the updated data to the database
						try (FullDataSourceV2DTO dto = this.createDtoFromDataSource(recipientDataSource)) {
							this.datastore.setFullData(dto.toDatastoreFullData(levelId));
						}


						for (Consumer<FullDataSourceV2> listener : this.dateSourceUpdateListeners) {
							if (listener != null) {
								listener.accept(recipientDataSource);
							}
						}
					}
				}
			}
		} catch (Exception e) {
			LOGGER.error("Error updating pos [" + DhSectionPos.toString(updatePos) + "], error: " + e.getMessage(), e);
		} finally {
			if (methodLocked) {
				updateLock.unlock();
				this.lockedPosSet.remove(updatePos);
			}
		}
	}

	/**
	 * Returns the {@link FullDataSourceV2} for the given section position. <Br>
	 * The returned data source may be null if repo is in the process of shutting down. <Br> <Br>
	 * <p>
	 * This call is concurrent. I.e. it supports being called by multiple threads at the same time.
	 */
	public CompletableFuture<FullDataSourceV2> getAsync(long pos) {
		AbstractExecutorService executor = ThreadPoolUtil.getFileHandlerExecutor();
		if (executor == null || executor.isTerminated()) {
			return CompletableFuture.completedFuture(null);
		}


		try {
			return CompletableFuture.supplyAsync(() -> this.get(pos), executor);
		} catch (RejectedExecutionException ignore) {
			// the thread pool was probably shut down because it's size is being changed, just wait a sec and it should be back
			return CompletableFuture.completedFuture(null);
		}
	}

	/**
	 * Should only be used in internal file handler methods where we are already running on a file handler thread.
	 * Can return null if the repo is in the process of being shut down
	 */
	public FullDataSourceV2 get(long pos) {
		Optional<FullDataSourceV2DTO> fullData = this.datastore.getFullData(
				this.levelId,
				DhSectionPos.getDetailLevel(pos),
				DhSectionPos.getX(pos),
				DhSectionPos.getZ(pos)
		).map(FullDataSourceV2DTO::new);

		if (!fullData.isPresent())
			return this.makeEmptyDataSource(pos);

		try {
			return this.createDataSourceFromDto(fullData.get());
		} catch (DataCorruptedException e) {
			// there's a rare issue where the exception doesn't
			// have a message, which can cause problems
			String message = (e.getMessage() == null) ? e.getMessage() : "No Error message for exception [" + e.getClass().getSimpleName() + "]";

			// Only log each message type once.
			// This is done to prevent logging "No compression mode with the value [2]" 10,000 times 
			// if the user is migrating from a nightly build and used ZStd.
			if (CORRUPT_DATA_ERRORS_LOGGED.add(message)) {
				LOGGER.warn("Corrupted data found at pos [" + DhSectionPos.toString(pos) + "]. Data at position will be deleted so it can be re-generated to prevent issues. Future errors with this same message won't be logged. Error: [" + message + "].", e);
			}

			this.datastore.deleteFullData(levelId, DhSectionPos.getDetailLevel(pos), DhSectionPos.getX(pos), DhSectionPos.getZ(pos));
		} catch (IOException e) {
			LOGGER.warn("File read Error for pos [" + DhSectionPos.toString(pos) + "], error: " + e.getMessage(), e);
		} catch (InterruptedException ignore) {
		}

		return null;
	}

	/**
	 * Can be used if the same thread is already handling IO and/or LOD generation.
	 * Otherwise the async version {@link FullDataSourceProviderV2#updateDataSourceAsync(FullDataSourceV2)} may be a better choice.
	 */
	public void updateDataSource(@NotNull FullDataSourceV2 inputDataSource) {
		this.updateDataSourceAtPos(inputDataSource.getPos(), inputDataSource, true);
	}

	//=======================//
	// data source migration //
	//=======================//

	private void convertLegacyDataSources() {
		try {
			String levelId = this.level.getLevelWrapper().getDhIdentifier();
			LOGGER.info("Attempting to migrate data sources for: [" + levelId + "]-[" + this.saveDir + "]...");
			this.migrationThreadRunning.set(true);


			//============================//
			// delete unused data sources //
			//============================//

			// this could be done all at once via SQL, 
			// but doing it in chunks prevents locking the database for long periods of time 
			long unusedCount = 0;
			long totalDeleteCount = this.legacyFileHandler.repo.getUnusedDataSourceCount();
			if (totalDeleteCount != 0) {
				// this should only be shown once per session but should be shown during 
				// either when the deletion or migration phases start
				this.showMigrationStartMessage();


				LOGGER.info("deleting [" + levelId + "] - [" + totalDeleteCount + "] unused data sources...");
				this.legacyDeletionCount = totalDeleteCount;

				ArrayList<String> unusedDataPosList = this.legacyFileHandler.repo.getUnusedDataSourcePositionStringList(50);
				while (unusedDataPosList.size() != 0) {
					unusedCount += unusedDataPosList.size();
					this.legacyDeletionCount -= unusedDataPosList.size();


					long startTime = System.currentTimeMillis();

					// delete batch and get next batch 
					this.legacyFileHandler.repo.deleteUnusedLegacyData(unusedDataPosList);
					unusedDataPosList = this.legacyFileHandler.repo.getUnusedDataSourcePositionStringList(50);

					long endStart = System.currentTimeMillis();
					long deleteTime = endStart - startTime;
					LOGGER.info("Deleting [" + levelId + "] - [" + unusedCount + "/" + totalDeleteCount + "] in [" + deleteTime + "]ms ...");


					// a slight delay is added to prevent accidentally locking the database when deleting a lot of rows
					// (that shouldn't be the case since we're using WAL journaling, but just in case)
					try {
						// use the delete time so we don't make powerful computers wait super long
						// and weak computers wait no time at all
						Thread.sleep(deleteTime / 2);
					} catch (InterruptedException ignore) {
					}
				}
				LOGGER.info("Done deleting [" + levelId + "] - [" + totalDeleteCount + "] unused data sources.");

			}


			//===========//
			// migration //
			//===========//

			long totalMigrationCount = this.legacyFileHandler.getDataSourceMigrationCount();
			this.migrationCount = totalMigrationCount;
			LOGGER.info("Found [" + totalMigrationCount + "] data sources that need migration.");

			ArrayList<FullDataSourceV1> legacyDataSourceList = this.legacyFileHandler.getDataSourcesToMigrate(MIGRATION_BATCH_COUNT);
			if (!legacyDataSourceList.isEmpty()) {
				this.showMigrationStartMessage();

				try {
					// keep going until every data source has been migrated
					int progressCount = 0;
					while (!legacyDataSourceList.isEmpty() && this.migrationThreadRunning.get()) {
						NumberFormat numFormat = F3Screen.NUMBER_FORMAT;
						LOGGER.info("Migrating [" + levelId + "] - [" + numFormat.format(progressCount) + "/" + numFormat.format(totalMigrationCount) + "]...");

						ArrayList<CompletableFuture<Void>> updateFutureList = new ArrayList<>();
						for (int i = 0; i < legacyDataSourceList.size() && this.migrationThreadRunning.get(); i++) {
							FullDataSourceV1 legacyDataSource = legacyDataSourceList.get(i);

							try {
								// convert the legacy data source to the new format,
								// this is a relatively cheap operation
								FullDataSourceV2 newDataSource = FullDataSourceV2.createFromLegacyDataSourceV1(legacyDataSource);
								newDataSource.applyToParent = true;

								// the actual update process can be moderately expensive due to having to update
								// the render data along with the full data, so running it async on the update threads gains us a good bit of speed
								CompletableFuture<Void> future = this.updateDataSourceAsync(newDataSource);
								updateFutureList.add(future);
								future.thenRun(() ->
								{
									// after the update finishes the legacy data source can be safely deleted
									this.legacyFileHandler.repo.deleteWithKey(legacyDataSource.getPos());
									newDataSource.close();
								});
							} catch (Exception e) {
								Long migrationPos = legacyDataSource.getPos();
								LOGGER.warn("Unexpected issue migrating data source at pos [" + DhSectionPos.toString(migrationPos) + "]. Error: " + e.getMessage(), e);
								this.legacyFileHandler.markMigrationFailed(migrationPos);
							}
						}


						try {
							// wait for each thread to finish updating
							CompletableFuture<Void> combinedFutures = CompletableFuture.allOf(updateFutureList.toArray(new CompletableFuture[0]));
							combinedFutures.get(MIGRATION_MAX_UPDATE_TIMEOUT_IN_MS, TimeUnit.MILLISECONDS);
						} catch (InterruptedException | TimeoutException e) {
							LOGGER.warn("Migration update timed out after [" + MIGRATION_MAX_UPDATE_TIMEOUT_IN_MS + "] milliseconds. Migration will re-try the same positions again in a moment.", e);
						} catch (ExecutionException e) {
							LOGGER.warn("Migration update failed. Migration will re-try the same positions again. Error:" + e.getMessage(), e);
						}

						legacyDataSourceList = this.legacyFileHandler.getDataSourcesToMigrate(MIGRATION_BATCH_COUNT);

						progressCount += legacyDataSourceList.size();
						this.migrationCount -= legacyDataSourceList.size();
					}
				} catch (Exception e) {
					LOGGER.info("migration stopped due to error for: [" + levelId + "]-[" + this.saveDir + "], error: [" + e.getMessage() + "].", e);
					this.showMigrationEndMessage(false);
					this.migrationStoppedWithError = true;
				} finally {
					if (this.migrationThreadRunning.get()) {
						LOGGER.info("migration complete for: [" + levelId + "]-[" + this.saveDir + "].");
						this.showMigrationEndMessage(true);
						this.migrationCount = 0;
					} else {
						LOGGER.info("migration stopped for: [" + levelId + "]-[" + this.saveDir + "].");
						this.showMigrationEndMessage(false);
						this.migrationStoppedWithError = true;
					}
				}
			} else {
				LOGGER.info("No migration necessary.");
			}
		} finally {
			this.migrationThreadRunning.set(false);
		}
	}

	/**
	 * Can be used if you don't want to lock the current thread
	 * Otherwise the sync version {@link FullDataSourceProviderV2#updateDataSource(FullDataSourceV2)} may be a better choice.
	 */
	public CompletableFuture<Void> updateDataSourceAsync(@NotNull FullDataSourceV2 inputDataSource) {
		AbstractExecutorService executor = ThreadPoolUtil.getChunkToLodBuilderExecutor();
		if (executor == null || executor.isTerminated()) {
			return CompletableFuture.completedFuture(null);
		}


		try {
			this.markUpdateStart(inputDataSource.getPos());
			return CompletableFuture.runAsync(() ->
			{
				try {
					this.updateDataSourceAtPos(inputDataSource.getPos(), inputDataSource, true);
				} catch (Exception e) {
					LOGGER.error("Unexpected error in async data source update at pos: [" + DhSectionPos.toString(inputDataSource.getPos()) + "], error: [" + e.getMessage() + "].", e);
				} finally {
					this.markUpdateEnd(inputDataSource.getPos());
				}
			}, executor);
		} catch (RejectedExecutionException ignore) {
			// can happen if the executor was shutdown while this task was queued
			this.markUpdateEnd(inputDataSource.getPos());
			return CompletableFuture.completedFuture(null);
		}
	}

	/**
	 * used for debugging to track which positions are queued for updating
	 */
	private void markUpdateStart(long dataSourcePos) {
		this.queuedUpdateCountsByPos.compute(dataSourcePos, (pos, atomicCount) ->
		{
			if (atomicCount == null) {
				atomicCount = new AtomicInteger(0);
			}
			atomicCount.incrementAndGet();
			return atomicCount;
		});
	}

	/**
	 * used for debugging to track which positions are queued for updating
	 */
	private void markUpdateEnd(long dataSourcePos) {
		this.queuedUpdateCountsByPos.compute(dataSourcePos, (pos, atomicCount) ->
		{
			if (atomicCount != null && atomicCount.decrementAndGet() <= 0) {
				atomicCount = null;
			}
			return atomicCount;
		});
	}

	public long getLegacyDeletionCount() {
		return this.legacyDeletionCount;
	}

	public long getTotalMigrationCount() {
		return this.migrationCount;
	}

	public boolean getMigrationStoppedWithError() {
		return this.migrationStoppedWithError;
	}


	private void showMigrationStartMessage() {
		if (this.migrationStartMessageQueued) {
			return;
		}
		this.migrationStartMessageQueued = true;

		String levelId = this.level.getLevelWrapper().getDhIdentifier();
		ClientApi.INSTANCE.showChatMessageNextFrame(
				"Old Distant Horizons data is being migrated for [" + levelId + "]. \n" +
				"While migrating LODs may load slowly \n" +
				"and DH world gen will be disabled. \n" +
				"You can see migration progress in the F3 menu."
		);
	}

	private void showMigrationEndMessage(boolean success) {
		String levelId = this.level.getLevelWrapper().getDhIdentifier();

		if (success) {
			ClientApi.INSTANCE.showChatMessageNextFrame("Distant Horizons data migration for [" + levelId + "] completed.");
		} else {
			ClientApi.INSTANCE.showChatMessageNextFrame(
					"Distant Horizons data migration for [" + levelId + "] stopped. \n" +
					"Some data may not have been migrated."
			);
		}
	}


	//=======================//
	// retrieval (world gen) //
	//=======================//

	/**
	 * Returns true if this provider can generate or retrieve
	 * {@link FullDataSourceV2}'s that aren't currently in the database.
	 */
	public boolean canRetrieveMissingDataSources() {
		// the base handler just handles basic reading/writing
		// to the database and as such can't retrieve anything else.
		return false;
	}

	/**
	 * Returns false if this provider isn't accepting new requests,
	 * this can be due to having a full queue or some other
	 * limiting factor. <br><br>
	 * <p>
	 * Note: when overriding make sure to add: <br>
	 * <code>
	 * if (!super.canQueueRetrieval()) <br>
	 * { <br>
	 * return false; <br>
	 * } <br>
	 * </code>
	 * to the beginning of your override.
	 * Otherwise, parent retrieval limits will be ignored.
	 */
	public boolean canQueueRetrieval() {
		// Retrieval shouldn't happen while an unknown number of
		// legacy data sources are present.
		// If retrieval was allowed we might run into concurrency issues.
		return !this.migrationThreadRunning.get();
	}

	/**
	 * @return null if this provider can't generate any positions and
	 * an empty array if all positions were generated
	 */
	@Nullable
	public LongArrayList getPositionsToRetrieve(Long pos) {
		return null;
	}

	/**
	 * @return true if the position was queued, false if not
	 */
	@Nullable
	public CompletableFuture<WorldGenResult> queuePositionForRetrieval(Long genPos) {
		return null;
	}

	/**
	 * does nothing if the given position isn't present in the queue
	 */
	public void removeRetrievalRequestIf(DhSectionPos.ICancelablePrimitiveLongConsumer removeIf) {
	}

	public void clearRetrievalQueue() {
	}

	/**
	 * Can be used to display how many total retrieval requests might be available.
	 */
	public void setTotalRetrievalPositionCount(int newCount) {
	}

	/**
	 * Can be used to display how many total chunk retrieval requests should be available.
	 */
	public void setEstimatedRemainingRetrievalChunkCount(int newCount) {
	}


	//========================//
	// multiplayer networking //
	//========================//

	@Nullable
	public Long getTimestampForPos(long pos) {
		Optional<IDhApiDatastore.FullDataMetadata> metadata = this.datastore.getFullDataMetadata(
				this.levelId,
				DhSectionPos.getDetailLevel(pos),
				DhSectionPos.getX(pos),
				DhSectionPos.getZ(pos)
		);

		if (!metadata.isPresent())
			return null;

		return metadata.map(m -> m.lastModified.getTime()).get();
	}


	//===========//
	// overrides //
	//===========//

	@Override
	public void debugRender(DebugRenderer renderer) {
		this.lockedPosSet
				.forEach((pos) -> {
					renderer.renderBox(new DebugRenderer.Box(pos, -32f, 74f, 0.15f, Color.PINK));
				});

		this.queuedUpdateCountsByPos
				.forEach((pos, updateCountRef) -> {
					renderer.renderBox(new DebugRenderer.Box(pos, -32f, 80f + (updateCountRef.get() * 16f), 0.20f, Color.WHITE));
				});
		this.updatingPosSet
				.forEach((pos) -> {
					renderer.renderBox(new DebugRenderer.Box(pos, -32f, 80f, 0.20f, Color.MAGENTA));
				});
	}

	public void close() {
		try {
			this.closeLock.lock();
			this.isShutdown = true;

			// wait a moment so any queued saves can finish queuing, 
			// otherwise we might not see everything that needs saving and attempt to use a closed repo
			Thread.sleep(200);

			LOGGER.info("Closing [" + this.getClass().getSimpleName() + "] for level: [" + this.level + "].");
		} catch (InterruptedException ignore) {
		} finally {
			this.closeLock.unlock();
		}

		if (this.updateQueueProcessor != null) {
			this.updateQueueProcessor.shutdownNow();
		}

		this.legacyFileHandler.close();

		this.migrationThreadRunning.set(false);
	}

}
