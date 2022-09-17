package org.apache.flink.runtime.spector.controller.impl;

import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.executiongraph.ExecutionGraph;
import org.apache.flink.runtime.executiongraph.ExecutionJobVertex;
import org.apache.flink.runtime.jobgraph.JobVertexID;
import org.apache.flink.runtime.spector.JobExecutionPlan;
import org.apache.flink.runtime.spector.JobReconfigActor;
import org.apache.flink.runtime.spector.controller.OperatorController;
import org.apache.flink.runtime.spector.controller.ReconfigExecutor;
import org.apache.flink.runtime.spector.controller.StateMigrationPlanner;
import org.apache.flink.runtime.state.KeyGroupRange;
import org.apache.flink.runtime.state.KeyGroupRangeAssignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class ControlPlane {

	private static final Logger LOG = LoggerFactory.getLogger(ControlPlane.class);

	public final static String SYNC_KEYS = "spector.reconfig.sync_keys";

	private final JobReconfigActor jobReconfigActor;

	private final Map<JobVertexID, OperatorController> controllers;

	private final int syncKeys;

	public ControlPlane(JobReconfigActor jobReconfigActor, ExecutionGraph executionGraph) {

		this.jobReconfigActor = jobReconfigActor;

		this.controllers = new HashMap<>(executionGraph.getAllVertices().size());



		Configuration config = executionGraph.getJobConfiguration();

		this.syncKeys = config.getInteger(SYNC_KEYS, 1);

//		this.migrationInterval = config.getLong("streamswitch.system.migration_interval", 5000);
		String targetOperatorsStr = config.getString("controller.target.operators", "flatmap");
		String reconfigStartStr = config.getString("spector.reconfig.start", "5000");
		List<String> targetOperatorsList = Arrays.asList(targetOperatorsStr.split(","));
		List<String> reconfigStartList = Arrays.asList(reconfigStartStr.split(","));
		Map<String, Integer> targetOperators = new HashMap<>(targetOperatorsList.size());
		for (int i = 0; i < targetOperatorsList.size(); i++) {
			targetOperators.put(targetOperatorsList.get(i), Integer.valueOf(reconfigStartList.get(i)));
		}

		for (Map.Entry<JobVertexID, ExecutionJobVertex> entry : executionGraph.getAllVertices().entrySet()) {
			JobVertexID vertexID = entry.getKey();
			int parallelism = entry.getValue().getParallelism();
			int maxParallelism = entry.getValue().getMaxParallelism();

			String operatorName = entry.getValue().getName();
			if (targetOperators.containsKey(operatorName)) {
				Map<String, List<String>> executorMapping = generateExecutorMapping(parallelism, maxParallelism);

				ReconfigExecutor reconfigExecutor = new ReconfigExecutorImpl(
					vertexID,
					parallelism,
					executorMapping);

				OperatorController controller = new DummyController(
					config,
					operatorName,
					targetOperators.get(operatorName),
					reconfigExecutor,
					executorMapping);

				controller.initMetrics(jobReconfigActor.getJobGraph(), vertexID, config, parallelism);
				this.controllers.put(vertexID, controller);
			}
		}
	}

	private static Map<String, List<String>> generateExecutorMapping(int parallelism, int maxParallelism) {
		Map<String, List<String>> executorMapping = new HashMap<>();

		int numExecutors = generateExecutorDelegates(parallelism).size();
		int numPartitions = generateFinestPartitionDelegates(maxParallelism).size();
		for (int executorId = 0; executorId < numExecutors; executorId++) {
			List<String> executorPartitions = new ArrayList<>();
			executorMapping.put(String.valueOf(executorId), executorPartitions);

			KeyGroupRange keyGroupRange = KeyGroupRangeAssignment.computeKeyGroupRangeForOperatorIndex(
				numPartitions, numExecutors, executorId);
			for (int i = keyGroupRange.getStartKeyGroup(); i <= keyGroupRange.getEndKeyGroup(); i++) {
				executorPartitions.add(String.valueOf(i));
			}
		}
		return executorMapping;
	}

	public void startControllers() {
		for (org.apache.flink.runtime.spector.controller.OperatorController controller : controllers.values()) {
			controller.start();
		}
	}

	public void stopControllers() {
		for (org.apache.flink.runtime.spector.controller.OperatorController controller : controllers.values()) {
			controller.stopGracefully();
		}
	}

	public void onChangeImplemented(JobVertexID jobVertexID) {
		LOG.info("++++++ onChangeImplemented triggered for jobVertex " + jobVertexID);
		this.controllers.get(jobVertexID).onMigrationCompleted();
	}

	public void onForceRetrieveMetrics(JobVertexID jobVertexID) {
		LOG.info("++++++ onForceRetrieveMetrics triggered for jobVertex " + jobVertexID);
		this.controllers.get(jobVertexID).onForceRetrieveMetrics();
	}

	public void onMigrationExecutorsStopped(JobVertexID jobVertexID) {
		LOG.info("++++++ onMigrationExecutorsStopped triggered for jobVertex " + jobVertexID);
		this.controllers.get(jobVertexID).onMigrationExecutorsStopped();
	}

	private static List<String> generateExecutorDelegates(int parallelism) {
		List<String> executors = new ArrayList<>();
		for (int i = 0; i < parallelism; i++) {
			executors.add(String.valueOf(i));
		}
		return executors;
	}

	private static List<String> generateFinestPartitionDelegates(int maxParallelism) {
		List<String> finestPartitions = new ArrayList<>();
		for (int i = 0; i < maxParallelism; i++) {
			finestPartitions.add(String.valueOf(i));
		}
		return finestPartitions;
	}

	private class ReconfigExecutorImpl implements ReconfigExecutor {

		private final JobVertexID jobVertexID;

		private int numOpenedSubtask;

		private JobExecutionPlan oldExecutionPlan;

		private Map<String, List<String>> oldExecutorMapping;


		public ReconfigExecutorImpl(JobVertexID jobVertexID, int parallelism, Map<String, List<String>> executorMapping) {
//			this.stateMigrationPlanner = new StateMigrationPlannerImpl(jobVertexID, parallelism);
			this.jobVertexID = jobVertexID;
			this.numOpenedSubtask = parallelism;

			this.oldExecutionPlan = new JobExecutionPlan(jobVertexID, executorMapping, numOpenedSubtask);
			// Deep copy
			this.oldExecutorMapping = new HashMap<>();
			for (String taskId : executorMapping.keySet()) {
				oldExecutorMapping.put(taskId, new ArrayList<>(executorMapping.get(taskId)));
			}
			jobReconfigActor.setInitialJobExecutionPlan(jobVertexID, oldExecutionPlan);
		}

		@Override
		public void remap(Map<String, List<String>> executorMapping) {
			handleTreatment(executorMapping);
		}

		@Override
		public void scale(int newParallelism, Map<String, List<String>> executorMapping) {
			handleTreatment(executorMapping);
		}

		private void handleTreatment(Map<String, List<String>> executorMapping) {
			int newParallelism = executorMapping.keySet().size();

			JobExecutionPlan jobExecutionPlan;

			if (numOpenedSubtask >= newParallelism) {
				// repartition
				jobExecutionPlan = new JobExecutionPlan(
					jobVertexID, executorMapping, oldExecutorMapping, oldExecutionPlan, numOpenedSubtask);

				// if there are multiple reconfig triggers, only one of them should be happened first.
				synchronized (jobReconfigActor) {
					jobReconfigActor.repartition(jobVertexID, jobExecutionPlan);
				}
			} else {
				// scale out
				jobExecutionPlan = new JobExecutionPlan(
					jobVertexID, executorMapping, oldExecutorMapping, oldExecutionPlan, newParallelism);
//				synchronized (rescaleAction) {
//					rescaleAction.scaleOut(jobVertexID, newParallelism, jobExecutionPlan);
//				}
				numOpenedSubtask = newParallelism;
			}

			this.oldExecutionPlan = jobExecutionPlan;
			this.oldExecutorMapping = new HashMap<>(executorMapping);
		}
	}

	/**
	 * Set the plan for the state migration according to the Configurations
	 */
	private class StateMigrationPlannerImpl implements StateMigrationPlanner {
		private final JobVertexID jobVertexID;

		private int numOpenedSubtask;

		private Map<String, List<String>> oldExecutorMapping;

		private final ReconfigExecutor reconfigExecutor;

		public StateMigrationPlannerImpl(JobVertexID jobVertexID, int parallelism, Map<String, List<String>> executorMapping) {
			this.jobVertexID = jobVertexID;
			this.numOpenedSubtask = parallelism;

			// Deep copy
			this.oldExecutorMapping = new HashMap<>();
			for (String taskId : executorMapping.keySet()) {
				oldExecutorMapping.put(taskId, new ArrayList<>(executorMapping.get(taskId)));
			}

			this.reconfigExecutor = new ReconfigExecutorImpl(
				jobVertexID,
				parallelism,
				executorMapping);
		}

		public void remap(Map<String, List<String>> executorMapping) {
			makePlan(executorMapping);
		}

		public void scale(Map<String, List<String>> executorMapping) {
			makePlan(executorMapping);
		}

		public void makePlan(Map<String, List<String>> executorMapping) {
			int newParallelism = executorMapping.keySet().size();

			// find out the affected keys.
			// order the migrating keys
			// return the state migration plan to reconfig executor


			Map<String, Tuple2<String, String>> affectedKeys = getAffectedKeys(executorMapping);


			prioritizeKeys(affectedKeys);
			List<Map<String, Tuple2<String, String>>> plan = batching(affectedKeys);


			if (numOpenedSubtask >= newParallelism) {
				// repartition
				for (Map<String, Tuple2<String, String>> batchedAffectedKeys : plan) {
					Map<String, List<String>> fluidExecutorMapping = new HashMap<>();
					for (String taskId : oldExecutorMapping.keySet()) {
						fluidExecutorMapping.put(taskId, new ArrayList<>(oldExecutorMapping.get(taskId)));
					}
					for (String affectedKey : batchedAffectedKeys.keySet()) {
						Tuple2<String, String> srcToDst = batchedAffectedKeys.get(affectedKey);

						fluidExecutorMapping.get(srcToDst.f0).remove(affectedKey);
						fluidExecutorMapping.get(srcToDst.f1).add(affectedKey);
					}
					// trigger reconfig Executor rescale
					reconfigExecutor.remap(fluidExecutorMapping);
				}
			} else {
				// scale out
				throw new UnsupportedOperationException();
			}



			this.oldExecutorMapping = new HashMap<>(executorMapping);
		}

		private Map<String, Tuple2<String, String>> getAffectedKeys(Map<String, List<String>> executorMapping) {
			// Key -> <SrcTaskId, DstTaskId>, can be used to set new ExecutorMapping
			Map<String, Tuple2<String, String>> affectedKeys = new HashMap<>();

			// Key -> Task
			Map<String, String> oldKeysToExecutorMapping = new HashMap<>();

			oldExecutorMapping.keySet().forEach(oldTaskId -> {
				for (String key : oldExecutorMapping.get(oldTaskId)) {
					oldKeysToExecutorMapping.put(key, oldTaskId);
				}
			});

			executorMapping.keySet().forEach(newTaskId -> {
				for (String key : executorMapping.get(newTaskId)) {
					// check whether the keys is migrated from old task to new task
					if (!oldKeysToExecutorMapping.get(key).equals(newTaskId)) {
						affectedKeys.put(key, Tuple2.of(oldKeysToExecutorMapping.get(key), newTaskId));
					}
				}
			});
			return affectedKeys;
		}

		private List<Map<String, Tuple2<String, String>>> batching(Map<String, Tuple2<String, String>> affectedKeys) {
			List<Map<String, Tuple2<String, String>>> plan = new ArrayList<>();

			int count = 0;
			Map<String, Tuple2<String, String>> batchedAffectedKeys = new HashMap<>();

			for (String affectedKey : affectedKeys.keySet()) {
				if (count % syncKeys == 0) {
					plan.add(batchedAffectedKeys);
					batchedAffectedKeys = new HashMap<>();
				}
				batchedAffectedKeys.put(affectedKey, affectedKeys.get(affectedKey));
				count++;
			}
			return plan;
		}

		public void prioritizeKeys(Map<String, Tuple2<String, String>> affectedKeys) {}


		private void compareAndSetAffectedKeys(Map<Integer, List<Integer>> executorMapping, Map<Integer,
			List<Integer>> oldExecutorMapping, Integer id, Map<Integer, List<Integer>> subtaskMap) {
			for (Integer hashedKeys : executorMapping.get(id)) {
				if (!oldExecutorMapping.get(id).contains(hashedKeys)) {
					List<Integer> keysToMigrateIn = subtaskMap.computeIfAbsent(id, t -> new ArrayList<>());
					keysToMigrateIn.add(hashedKeys);
				}
			}
		}
	}
}
