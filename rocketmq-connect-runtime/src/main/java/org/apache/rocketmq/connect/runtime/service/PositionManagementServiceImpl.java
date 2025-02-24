/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.rocketmq.connect.runtime.service;

import io.netty.util.internal.ConcurrentSet;
import io.openmessaging.connector.api.data.RecordOffset;
import io.openmessaging.connector.api.data.RecordPartition;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.rocketmq.connect.runtime.config.ConnectConfig;
import org.apache.rocketmq.connect.runtime.converter.JsonConverter;
import org.apache.rocketmq.connect.runtime.converter.RecordOffsetConverter;
import org.apache.rocketmq.connect.runtime.converter.RecordPartitionConverter;
import org.apache.rocketmq.connect.runtime.converter.RecordPositionMapConverter;
import org.apache.rocketmq.connect.runtime.store.FileBaseKeyValueStore;
import org.apache.rocketmq.connect.runtime.store.KeyValueStore;
import org.apache.rocketmq.connect.runtime.utils.ConnectUtil;
import org.apache.rocketmq.connect.runtime.utils.FilePathConfigUtil;
import org.apache.rocketmq.connect.runtime.utils.datasync.BrokerBasedLog;
import org.apache.rocketmq.connect.runtime.utils.datasync.DataSynchronizer;
import org.apache.rocketmq.connect.runtime.utils.datasync.DataSynchronizerCallback;

public class PositionManagementServiceImpl implements PositionManagementService {

    /**
     * Current position info in store.
     */
    private KeyValueStore<RecordPartition, RecordOffset> positionStore;

    /**
     * The updated partition of the task in the current instance.
     * */
    private Set<RecordPartition> needSyncPartition;

    /**
     * Synchronize data with other workers.
     */
    private DataSynchronizer<String, Map<RecordPartition, RecordOffset>> dataSynchronizer;

    /**
     * Listeners.
     */
    private Set<PositionUpdateListener> positionUpdateListener;

    private final String positionManagePrefix = "PositionManage";

    public PositionManagementServiceImpl(ConnectConfig connectConfig) {

        this.positionStore = new FileBaseKeyValueStore<>(FilePathConfigUtil.getPositionPath(connectConfig.getStorePathRootDir()),
            new RecordPartitionConverter(),
            new RecordOffsetConverter());
        this.dataSynchronizer = new BrokerBasedLog(connectConfig,
            connectConfig.getPositionStoreTopic(),
            ConnectUtil.createGroupName(positionManagePrefix, connectConfig.getWorkerId()),
            new PositionChangeCallback(),
            new JsonConverter(),
            new RecordPositionMapConverter());
        this.positionUpdateListener = new HashSet<>();
        this.needSyncPartition = new ConcurrentSet<>();
    }

    @Override
    public void start() {

        positionStore.load();
        dataSynchronizer.start();
        sendOnlinePositionInfo();
    }

    @Override
    public void stop() {

        sendNeedSynchronizePosition();
        positionStore.persist();
        dataSynchronizer.stop();
    }

    @Override
    public void persist() {

        positionStore.persist();
    }

    @Override public void load() {
        positionStore.load();
    }

    @Override
    public void synchronize() {

        sendNeedSynchronizePosition();
    }

    @Override
    public Map<RecordPartition, RecordOffset> getPositionTable() {

        return positionStore.getKVMap();
    }

    @Override
    public RecordOffset getPosition(RecordPartition partition) {

        return positionStore.get(partition);
    }

    @Override
    public void putPosition(Map<RecordPartition, RecordOffset> positions) {

        positionStore.putAll(positions);
        needSyncPartition.addAll(positions.keySet());
    }

    @Override
    public void putPosition(RecordPartition partition, RecordOffset position) {

        positionStore.put(partition, position);
        needSyncPartition.add(partition);
    }

    @Override
    public void removePosition(List<RecordPartition> partitions) {

        if (null == partitions) {
            return;
        }

        for (RecordPartition partition : partitions) {
            needSyncPartition.remove(partition);
            positionStore.remove(partition);
        }
    }

    @Override
    public void registerListener(PositionUpdateListener listener) {

        this.positionUpdateListener.add(listener);
    }

    private void sendOnlinePositionInfo() {

        dataSynchronizer.send(PositionChangeEnum.ONLINE_KEY.name(), positionStore.getKVMap());
    }


    private void sendNeedSynchronizePosition() {

        Set<RecordPartition> needSyncPartitionTmp = needSyncPartition;
        needSyncPartition = new ConcurrentSet<>();
        Map<RecordPartition, RecordOffset> needSyncPosition = positionStore.getKVMap().entrySet().stream()
            .filter(entry -> needSyncPartitionTmp.contains(entry.getKey()))
            .collect(Collectors.toMap(entry -> entry.getKey(), entry -> entry.getValue()));

        dataSynchronizer.send(PositionChangeEnum.POSITION_CHANG_KEY.name(), needSyncPosition);
    }

    private void sendSynchronizePosition() {

        dataSynchronizer.send(PositionChangeEnum.POSITION_CHANG_KEY.name(), positionStore.getKVMap());
    }

    private class PositionChangeCallback implements DataSynchronizerCallback<String, Map<RecordPartition, RecordOffset>> {

        @Override
        public void onCompletion(Throwable error, String key, Map<RecordPartition, RecordOffset> result) {

            boolean changed = false;
            switch (PositionChangeEnum.valueOf(key)) {
                case ONLINE_KEY:
                    changed = true;
                    sendSynchronizePosition();
                    break;
                case POSITION_CHANG_KEY:
                    changed = mergePositionInfo(result);
                    break;
                default:
                    break;
            }
            if (changed) {
                triggerListener();
            }

        }
    }

    private void triggerListener() {
        for (PositionUpdateListener positionUpdateListener : positionUpdateListener) {
            positionUpdateListener.onPositionUpdate();
        }
    }

    /**
     * Merge new received position info with local store.
     *
     * @param result
     * @return
     */
    private boolean mergePositionInfo(Map<RecordPartition, RecordOffset> result) {

        boolean changed = false;
        if (null == result || 0 == result.size()) {
            return changed;
        }

        for (Map.Entry<RecordPartition, RecordOffset> newEntry : result.entrySet()) {
            boolean find = false;
            for (Map.Entry<RecordPartition, RecordOffset> existedEntry : positionStore.getKVMap().entrySet()) {
                if (newEntry.getKey().equals(existedEntry.getKey())) {
                    find = true;
                    if (!newEntry.getValue().equals(existedEntry.getValue())) {
                        changed = true;
                        existedEntry.setValue(newEntry.getValue());
                    }
                    break;
                }
            }
            if (!find) {
                positionStore.put(newEntry.getKey(), newEntry.getValue());
            }
        }
        return changed;
    }

    private enum PositionChangeEnum {

        /**
         * Insert or update position info.
         */
        POSITION_CHANG_KEY,

        /**
         * A worker online.
         */
        ONLINE_KEY
    }
}

