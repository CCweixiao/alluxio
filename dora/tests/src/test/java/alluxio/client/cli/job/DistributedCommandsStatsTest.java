/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.client.cli.job;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import alluxio.AlluxioURI;
import alluxio.Constants;
import alluxio.UnderFileSystemFactoryRegistryRule;
import alluxio.annotation.dora.DoraTestTodoItem;
import alluxio.client.file.FileSystemTestUtils;
import alluxio.grpc.WritePType;
import alluxio.job.plan.load.LoadConfig;
import alluxio.job.util.JobTestUtils;
import alluxio.job.wire.Status;
import alluxio.metrics.MetricKey;
import alluxio.metrics.MetricsSystem;
import alluxio.testutils.underfs.sleeping.SleepingUnderFileSystemFactory;
import alluxio.testutils.underfs.sleeping.SleepingUnderFileSystemOptions;

import com.google.common.collect.Sets;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Collections;

/**
 * Tests stat counter values and output of CANCEL operations for distributed commands.
 * The tests will depend on the timing of the actual job runs.
 * If the job completes fast enough before the CANCEL operations runs,then the test would fail.
 * The tests compare the job statuses (CANCEL or not) and stat counter values for each status.
 */
@DoraTestTodoItem(action = DoraTestTodoItem.Action.REMOVE, owner = "Jianjian",
    comment = "Job master and job worker no longer exists in dora")
@Ignore
public class DistributedCommandsStatsTest extends JobShellTest {
  private static final long SLEEP_MS = Constants.SECOND_MS * 15;
  private static final int TEST_TIMEOUT = 45;

  @ClassRule
  public static UnderFileSystemFactoryRegistryRule sUnderfilesystemfactoryregistry =
        new UnderFileSystemFactoryRegistryRule(new SleepingUnderFileSystemFactory(
            new SleepingUnderFileSystemOptions()
                .setIsDirectoryMs(SLEEP_MS)
                .setIsFileMs(SLEEP_MS)
                .setGetStatusMs(SLEEP_MS)
                .setListStatusMs(SLEEP_MS)
                .setListStatusWithOptionsMs(SLEEP_MS)
                .setExistsMs(SLEEP_MS)
                .setMkdirsMs(SLEEP_MS)));

  @Rule
  public TemporaryFolder mTempFolder = new TemporaryFolder();

  private String mLocalUfsPath;

  @Before
  public void before() throws Exception {
    mLocalUfsPath = mTempFolder.getRoot().getAbsolutePath();
    sFileSystem.mount(new AlluxioURI("/mnt/"), new AlluxioURI("sleep://" + mLocalUfsPath));
  }

  @Test
  public void testCompleteStats() throws Exception {
    final int length = 10;
    FileSystemTestUtils.createByteFile(sFileSystem, "/test", WritePType.THROUGH, length);

    long jobId = sJobMaster.run(new LoadConfig("/test", 1, Collections.EMPTY_SET,
            Collections.EMPTY_SET, Collections.EMPTY_SET, Collections.EMPTY_SET, false));

    JobTestUtils
            .waitForJobStatus(sJobMaster, jobId, Sets.newHashSet(Status.COMPLETED), TEST_TIMEOUT);

    sJobShell.run("stat", "-v", Long.toString(jobId));

    String[] output = mOutput.toString().split("\n");
    assertEquals(String.format("ID: %s", jobId), output[0]);
    assertEquals(String.format("Name: Load"), output[1]);
    assertTrue(output[2].contains("Description: LoadConfig"));
    assertTrue(output[2].contains("/test"));
    assertEquals("Status: COMPLETED", output[3]);
    assertEquals("Task 0", output[4]);
    assertTrue(output[5].contains("\tWorker: "));
    assertEquals("\tStatus: COMPLETED", output[7]);

    double completedCount = MetricsSystem.getMetricValue(
            MetricKey.MASTER_JOB_DISTRIBUTED_LOAD_SUCCESS.getName()).getValue();
    double fileCount = MetricsSystem.getMetricValue(
            MetricKey.MASTER_JOB_DISTRIBUTED_LOAD_FILE_COUNT.getName()).getValue();
    double fileSize = MetricsSystem.getMetricValue(
            MetricKey.MASTER_JOB_DISTRIBUTED_LOAD_FILE_SIZE.getName()).getValue();

    //Metrics for Migrate job type
    double completedMigrateCount = MetricsSystem.getMetricValue(
            MetricKey.MASTER_MIGRATE_JOB_SUCCESS.getName()).getValue();
    double completedMigrateFileCount = MetricsSystem.getMetricValue(
            MetricKey.MASTER_MIGRATE_JOB_FILE_COUNT.getName()).getValue();
    double completedMigrateFileSize = MetricsSystem.getMetricValue(
            MetricKey.MASTER_MIGRATE_JOB_FILE_SIZE.getName()).getValue();

    //Metrics for Persist job type
    double completedPersistCount = MetricsSystem.getMetricValue(
            MetricKey.MASTER_ASYNC_PERSIST_SUCCESS.getName()).getValue();
    double completedPersistFileCount = MetricsSystem.getMetricValue(
            MetricKey.MASTER_ASYNC_PERSIST_FILE_COUNT.getName()).getValue();
    double completedPersistFileSize = MetricsSystem.getMetricValue(
            MetricKey.MASTER_ASYNC_PERSIST_FILE_SIZE.getName()).getValue();

    //test counters for distributed load on Complete status.
    assertEquals(completedCount, 1, 0); //distributedLoad operation count equals 1.
    assertEquals(fileCount, 1, 0); // file count equals 1.
    assertEquals(fileSize, length, 0); // file size equals $length.

    //test for other job types. Migrate counters, should all be 0.
    assertEquals(completedMigrateCount, 0, 0);
    assertEquals(completedMigrateFileCount, 0, 0);
    assertEquals(completedMigrateFileSize, 0, 0);

    //test AsyncPersist counters, should all be 0.
    assertEquals(completedPersistCount, 0, 0);
    assertEquals(completedPersistFileCount, 0, 0);
    assertEquals(completedPersistFileSize, 0, 0);
  }
}
