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

package org.apache.flink.runtime.spector.netty;

import org.apache.flink.api.common.time.Time;
import org.apache.flink.core.memory.DataInputViewStreamWrapper;
import org.apache.flink.runtime.spector.netty.data.TaskAcknowledgement;
import org.apache.flink.runtime.spector.netty.data.TaskBackupState;
import org.apache.flink.runtime.spector.netty.data.TaskDeployment;
import org.apache.flink.runtime.taskexecutor.TaskExecutorGateway;
import org.apache.flink.shaded.netty4.io.netty.channel.ChannelHandlerContext;
import org.apache.flink.shaded.netty4.io.netty.channel.ChannelInboundHandlerAdapter;

import java.io.ByteArrayInputStream;

import static org.apache.flink.runtime.spector.netty.utils.NettySocketUtils.chunkedChannelRead;

/**
 * Server handler in task executor netty server.
 */
public class TaskExecutorServerHandler extends ChannelInboundHandlerAdapter {
	private static final Time DEFAULT_RPC_TIMEOUT = Time.seconds(10);
	private final TaskExecutorGateway taskExecutorGateway;

	final byte[][] recv = {new byte[0]};
	final int[] position = {0};

	public TaskExecutorServerHandler(TaskExecutorGateway taskExecutorGateway) {
		this.taskExecutorGateway = taskExecutorGateway;
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
//		if (msg instanceof TaskDeployment) {
//			TaskDeployment taskDeployment = (TaskDeployment) msg;
//			taskExecutorGateway.reconfigTask(
//				taskDeployment.getExecutionAttemptID(),
//				taskDeployment.getTaskDeploymentDescriptor(),
//				taskDeployment.getJobMasterId(),
//				taskDeployment.getReconfigOptions(),
//				DEFAULT_RPC_TIMEOUT);
//		} else if (msg instanceof TaskBackupState) {
//			TaskBackupState taskBackupState = (TaskBackupState) msg;
//			taskExecutorGateway.dispatchStateToStandbyTask(
//				taskBackupState.getExecutionAttemptID(),
//				taskBackupState.getJobvertexId(),
//				taskBackupState.getTaskRestore(),
//				taskBackupState.getTimeout()
//			);
//		}  else {
//			throw new UnsupportedOperationException();
//		}
		chunkedChannelRead(msg, this::fireAck, recv, position);
	}

	private void fireAck(byte[] bytes) {
		try {
			TaskBackupState taskBackupState = new TaskBackupState();
			taskBackupState.read(new DataInputViewStreamWrapper(new ByteArrayInputStream(bytes)));
			taskExecutorGateway.dispatchStateToStandbyTask(
				taskBackupState.getExecutionAttemptID(),
				taskBackupState.getJobvertexId(),
				taskBackupState.getTaskRestore(),
				taskBackupState.getTimeout());
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
