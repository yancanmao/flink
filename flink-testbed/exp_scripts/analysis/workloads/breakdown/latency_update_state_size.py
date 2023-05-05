from math import floor
import os

from analysis.config.default_config import timers_plot, per_task_rate, parallelism, per_key_state_size, \
    replicate_keys_filter, state_access_ratio, max_parallelism, FILE_FOLER, order_function, zipf_skew, sync_keys
from analysis.config.general_utilities import DrawFigureV4, breakdown_total


def ReadFile(repeat_num = 1):
    w, h = 6, 3
    y = [[] for y in range(h)]
    # y = []

    for repeat in range(1, repeat_num + 1):
        for per_key_state_size in [1024, 2048, 4096, 8196, 16384, 32768]:
            latency_dict = {}
            for replicate_keys_filter in [1, 2, 4]:
                col = []
                coly = []
                start_ts = float('inf')
                temp_dict = {}
                for tid in range(0, parallelism):
                    f = open(FILE_FOLER + '/workloads/spector-{}-{}-{}-{}-{}-{}-{}/Splitter FlatMap-{}.output'
                            .format(per_task_rate, parallelism, max_parallelism, per_key_state_size, \
                                    sync_keys, replicate_keys_filter, state_access_ratio, tid))
                    read = f.readlines()
                    for r in read:
                        if r.find("endToEnd latency: ") != -1:
                            ts = int(int(r.split("ts: ")[1][:13])/1000)
                            if ts < start_ts: # find the starting point from parallel tasks
                                start_ts = ts
                            latency = int(r.split("endToEnd latency: ")[1])
                            if ts not in temp_dict:
                                temp_dict[ts] = []
                            temp_dict[ts].append(latency)

                for ts in temp_dict:
                    # coly.append(sum(temp_dict[ts]) / len(temp_dict[ts]))
                    temp_dict[ts].sort()
                    coly.append(temp_dict[ts][floor((len(temp_dict[ts]))*0.99)])
                    col.append(ts - start_ts)

                # Get P95 latency
                coly.sort()
                # latency_dict[replicate_keys_filter] = coly[floor(len(coly)*0.99)]
                latency_dict[replicate_keys_filter] = coly[-1]


            print(latency_dict)
            i = 0
            for latency in latency_dict.values():
                y[i].append(latency)
                i += 1


    return y


def draw():
    # runtime, per_task_rate, parallelism, key_set, per_key_state_size, reconfig_interval, reconfig_type, affected_tasks, repeat_num = val

    # parallelism
    # x_values = [1024, 10240, 20480, 40960]
    # x_values = [1000, 2000, 4000, 5000, 6000]
    x_values = ["32K", "64K", "128K", "256K", "512K", "1024K"]
    y_values = ReadFile(repeat_num = 1)

    legend_labels = ["Repl-100%", "Repl-50%", "Repl-25%"]

    print(y_values)

    DrawFigureV4(x_values, y_values, legend_labels,
                         'Per Key State Size (Byte)', 'Latency (ms)',
                         'latency_update_state_size', True)
