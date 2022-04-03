/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.spector.reconfig;

import org.apache.flink.api.common.JobID;
import org.apache.flink.runtime.deployment.InputChannelDeploymentDescriptor;
import org.apache.flink.runtime.deployment.InputGateDeploymentDescriptor;
import org.apache.flink.runtime.deployment.ResultPartitionDeploymentDescriptor;
import org.apache.flink.runtime.deployment.ResultPartitionLocation;
import org.apache.flink.runtime.executiongraph.ExecutionAttemptID;
import org.apache.flink.runtime.io.disk.iomanager.IOManager;
import org.apache.flink.runtime.io.network.NetworkEnvironment;
import org.apache.flink.runtime.io.network.api.writer.ResultPartitionWriter;
import org.apache.flink.runtime.io.network.partition.ResultPartition;
import org.apache.flink.runtime.io.network.partition.ResultPartitionConsumableNotifier;
import org.apache.flink.runtime.io.network.partition.ResultPartitionID;
import org.apache.flink.runtime.io.network.partition.consumer.InputChannel;
import org.apache.flink.runtime.io.network.partition.consumer.LocalInputChannel;
import org.apache.flink.runtime.io.network.partition.consumer.RemoteInputChannel;
import org.apache.flink.runtime.io.network.partition.consumer.SingleInputGate;
import org.apache.flink.runtime.metrics.groups.TaskMetricGroup;
import org.apache.flink.runtime.taskmanager.TaskActions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

public class TaskConfigManager {

	private static final Logger LOG = LoggerFactory.getLogger(TaskConfigManager.class);

	private final JobID jobId;

	private final ExecutionAttemptID executionId;

	private final String taskNameWithSubtaskAndId;

	private final TaskActions taskActions;

	private final NetworkEnvironment network;

	private final IOManager ioManager;

	private final TaskMetricGroup metrics;

	private final ResultPartitionConsumableNotifier resultPartitionConsumableNotifier;

	private volatile TaskRescaleMeta rescaleMeta;

	private volatile ResultPartition[] storedOldWriterCopies;

	public TaskConfigManager(
			JobID jobId,
			ExecutionAttemptID executionId,
			String taskNameWithSubtaskAndId,
			TaskActions taskActions,
			NetworkEnvironment network,
			IOManager ioManager,
			TaskMetricGroup metrics,
			ResultPartitionConsumableNotifier notifier) {

		this.jobId = checkNotNull(jobId);
		this.executionId = checkNotNull(executionId);
		this.taskNameWithSubtaskAndId = checkNotNull(taskNameWithSubtaskAndId);
		this.taskActions = checkNotNull(taskActions);
		this.network = checkNotNull(network);
		this.ioManager = checkNotNull(ioManager);
		this.metrics = checkNotNull(metrics);
		this.resultPartitionConsumableNotifier = checkNotNull(notifier);
	}

	public void prepareRescaleMeta(
			ReconfigID reconfigId,
			ReconfigOptions reconfigOptions,
			Collection<ResultPartitionDeploymentDescriptor> resultPartitionDeploymentDescriptors,
			Collection<InputGateDeploymentDescriptor> inputGateDeploymentDescriptors) {

		TaskRescaleMeta meta = new TaskRescaleMeta(
			reconfigId,
			reconfigOptions,
			resultPartitionDeploymentDescriptors,
			inputGateDeploymentDescriptors);

		long timeStart = System.currentTimeMillis();
		while (rescaleMeta != null) {
			if (System.currentTimeMillis() - timeStart > 1000) {
				throw new IllegalStateException("One rescaling is in process, cannot prepare another rescaleMeta for " + taskNameWithSubtaskAndId);
			}
		}
		rescaleMeta = meta;
	}

	public boolean isScalingTarget() {
		return rescaleMeta != null;
	}

	public boolean isScalingPartitions() {
		return rescaleMeta.getReconfigOptions().isScalingPartitions();
	}

	public boolean isScalingGates() {
		return rescaleMeta.getReconfigOptions().isScalingGates();
	}

	public void createNewResultPartitions() throws IOException {
		int counter = 0;
		for (ResultPartitionDeploymentDescriptor desc : rescaleMeta.getResultPartitionDeploymentDescriptors()) {
			ResultPartitionID partitionId = new ResultPartitionID(desc.getPartitionId(), executionId, rescaleMeta.getReconfigId());

			ResultPartition newPartition = new ResultPartition(
				taskNameWithSubtaskAndId,
				taskActions,
				jobId,
				partitionId,
				desc.getPartitionType(),
				desc.getNumberOfSubpartitions(),
				desc.getMaxParallelism(),
				network.getResultPartitionManager(),
				resultPartitionConsumableNotifier,
				ioManager,
				desc.sendScheduleOrUpdateConsumersMessage());

			rescaleMeta.addNewPartitions(counter, newPartition);
			network.setupPartition(newPartition);

			++counter;
		}
	}

	public ResultPartitionWriter[] substituteResultPartitions(ResultPartitionWriter[] oldWriters) {
		ResultPartitionWriter[] oldWriterCopies = Arrays.copyOf(oldWriters, oldWriters.length);

		for (int i = 0; i < oldWriters.length; i++) {
			oldWriters[i] = rescaleMeta.getNewPartitions(i);
		}

		return oldWriterCopies;
	}

	// We cannot do it immediately because downstream's gate is still polling from the old partitions (barrier haven't pass to downstream)
	// so we store the oldWriterCopies and unregister them in next scaling.
	public void unregisterPartitions(ResultPartition[] oldWriterCopies) {
		if (storedOldWriterCopies != null) {
			network.unregisterPartitions(storedOldWriterCopies);
		}

		storedOldWriterCopies = oldWriterCopies;
	}

	public void substituteInputGateChannels(SingleInputGate inputGate) throws IOException, InterruptedException {
		checkNotNull(rescaleMeta, "rescale component cannot be null");

		InputGateDeploymentDescriptor igdd = rescaleMeta.getMatchedInputGateDescriptor(inputGate);
		InputChannelDeploymentDescriptor[] icdd = checkNotNull(igdd.getInputChannelDeploymentDescriptors());

//		inputGate.releaseAllResources();
		inputGate.reset(icdd.length);

		createChannel(inputGate, icdd);

//		network.setupInputGate(inputGate);

		inputGate.requestPartitions();
	}

	private void createChannel(SingleInputGate inputGate, InputChannelDeploymentDescriptor[] icdd) {
		for (int i = 0; i < icdd.length; i++) {
			final ResultPartitionID partitionId = icdd[i].getConsumedPartitionId();
			final ResultPartitionLocation partitionLocation = icdd[i].getConsumedPartitionLocation();

			InputChannel inputChannel = null;
			if (partitionLocation.isLocal()) {
				inputChannel = new LocalInputChannel(inputGate, i, partitionId,
					network.getResultPartitionManager(),
					network.getTaskEventDispatcher(),
					network.getPartitionRequestInitialBackoff(),
					network.getPartitionRequestMaxBackoff(),
					metrics.getIOMetricGroup()
				);
			}
			else if (partitionLocation.isRemote()) {
				inputChannel = new RemoteInputChannel(inputGate, i, partitionId,
					partitionLocation.getConnectionId(),
					network.getConnectionManager(),
					network.getPartitionRequestInitialBackoff(),
					network.getPartitionRequestMaxBackoff(),
					metrics.getIOMetricGroup()
				);
			}
			else {
				throw new IllegalStateException("Unexpected partition location.");
			}

			inputGate.setInputChannel(partitionId.getPartitionId(), inputChannel);
		}
	}

	public void finish() {
		this.rescaleMeta = null;
		LOG.info("++++++ taskRescaleManager finish, set meta to null for task " + taskNameWithSubtaskAndId);
	}

	public NetworkEnvironment getNetwork() {
		return network;
	}

	private static class TaskRescaleMeta {
		private final ReconfigID reconfigId;
		private final ReconfigOptions reconfigOptions;

		private final Collection<ResultPartitionDeploymentDescriptor> resultPartitionDeploymentDescriptors;
		private final Collection<InputGateDeploymentDescriptor> inputGateDeploymentDescriptors;

		private final ResultPartition[] newPartitions;

		TaskRescaleMeta(
				ReconfigID ReconfigID,
				ReconfigOptions ReconfigOptions,
				Collection<ResultPartitionDeploymentDescriptor> resultPartitionDeploymentDescriptors,
				Collection<InputGateDeploymentDescriptor> inputGateDeploymentDescriptors) {

			this.reconfigId = checkNotNull(ReconfigID);
			this.reconfigOptions = checkNotNull(ReconfigOptions);

			this.resultPartitionDeploymentDescriptors = checkNotNull(resultPartitionDeploymentDescriptors);
			this.inputGateDeploymentDescriptors = checkNotNull(inputGateDeploymentDescriptors);

			this.newPartitions = new ResultPartition[resultPartitionDeploymentDescriptors.size()];
		}

		public ReconfigID getReconfigId() {
			return reconfigId;
		}

		public ReconfigOptions getReconfigOptions() {
			return reconfigOptions;
		}

		public Collection<ResultPartitionDeploymentDescriptor> getResultPartitionDeploymentDescriptors() {
			return resultPartitionDeploymentDescriptors;
		}

		public Collection<InputGateDeploymentDescriptor> getInputGateDeploymentDescriptors() {
			return inputGateDeploymentDescriptors;
		}

		public ResultPartition getNewPartitions(int index) {
			checkState(index >= 0 && index < newPartitions.length, "given index out of boundary");

			return newPartitions[index];
		}

		public void addNewPartitions(int index, ResultPartition partition) {
			checkState(index >= 0 && index < newPartitions.length, "given index out of boundary");

			newPartitions[index] = partition;
		}

		/**
		 * Get matched InputGateDescriptor of the input gate
		 * <p>
		 * It just need to compare the `comsumedResultId`. There will be never two InputGateDeploymentDescriptor with
		 * the same `consumedResultId` which will actually be sent to different parallel operator instance.
		 * <p>
		 * We should not compare the `consumedSubPartitionIndex` here since the original partition type
		 * between previous operator and current may be `FORWARD`.
		 * <p>
		 * In that case, all its parallel operator instances has `consumedSubpartitionIndex` zero.
		 * However, the new deployment descriptor may set the new `consumedSubpartitionIndex` greater than zero
		 * which will cause gate would never find its new InputGateDeploymentDescriptor.
		 *
		 * @param gate
		 * @return
		 */
		public InputGateDeploymentDescriptor getMatchedInputGateDescriptor(SingleInputGate gate) {
			List<InputGateDeploymentDescriptor> igdds = new ArrayList<>();
			for (InputGateDeploymentDescriptor igdd : inputGateDeploymentDescriptors) {
				if (gate.getConsumedResultId().equals(igdd.getConsumedResultId())) {
					igdds.add(igdd);
				}
			}
			if (igdds.size() != 1) {
				throw new IllegalStateException("Cannot find matched InputGateDeploymentDescriptor");
			}
			gate.setConsumedSubpartitionIndex(igdds.get(0).getConsumedSubpartitionIndex());
			return igdds.get(0);
		}
//		public InputGateDeploymentDescriptor getMatchedInputGateDescriptor(SingleInputGate gate) {
//			for (InputGateDeploymentDescriptor igdd : inputGateDeploymentDescriptors) {
//				if (gate.getConsumedResultId().equals(igdd.getConsumedResultId())
//					&& gate.getConsumedSubpartitionIndex() == igdd.getConsumedSubpartitionIndex()) {
//					return igdd;
//				}
//			}
//			throw new IllegalStateException("Cannot find matched InputGateDeploymentDescriptor");
//		}
	}
}
