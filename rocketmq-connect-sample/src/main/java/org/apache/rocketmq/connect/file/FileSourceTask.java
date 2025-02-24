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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.connect.file;

import io.openmessaging.KeyValue;
import io.openmessaging.connector.api.component.task.source.SourceTask;
import io.openmessaging.connector.api.component.task.source.SourceTaskContext;
import io.openmessaging.connector.api.data.ConnectRecord;
import io.openmessaging.connector.api.data.Field;
import io.openmessaging.connector.api.data.FieldType;
import io.openmessaging.connector.api.data.RecordOffset;
import io.openmessaging.connector.api.data.RecordPartition;
import io.openmessaging.connector.api.data.Schema;
import io.openmessaging.connector.api.errors.ConnectException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.rocketmq.connect.file.FileConfig.FILE_CONFIG;
import static org.apache.rocketmq.connect.file.FileConstants.LINE;
import static org.apache.rocketmq.connect.file.FileConstants.NEXT_POSITION;

public class FileSourceTask extends SourceTask {

    private Logger log = LoggerFactory.getLogger(LoggerName.FILE_CONNECTOR);

    private FileConfig fileConfig;

    private InputStream stream;
    private BufferedReader reader = null;
    private char[] buffer = new char[1024];
    private int offset = 0;
    private int batchSize = FileSourceConnector.DEFAULT_TASK_BATCH_SIZE;

    private Long streamOffset;

    private KeyValue config;

    @Override public List<ConnectRecord> poll() {
        log.info("Start a poll stream is null:{}", stream == null);
        if (stream == null) {
            try {
                stream = Files.newInputStream(Paths.get(fileConfig.getFilename()));
                RecordOffset positionInfo = this.sourceTaskContext.offsetStorageReader().readOffset(offsetKey(FileConstants.getPartition(fileConfig.getFilename())));
                if (positionInfo != null && null != positionInfo.getOffset()) {
                    log.info("positionInfo is not null!");
                    Map<String, ?> offset = (Map<String, String>) positionInfo.getOffset();
                    Object lastRecordedOffset = offset.get(NEXT_POSITION);
                    if (lastRecordedOffset != null) {
                        log.debug("Found previous offset, trying to skip to file offset {}", lastRecordedOffset);
                        long skipLeft = Long.valueOf(String.valueOf(lastRecordedOffset));
                        while (skipLeft > 0) {
                            try {
                                long skipped = stream.skip(skipLeft);
                                skipLeft -= skipped;
                            } catch (IOException e) {
                                log.error("Error while trying to seek to previous offset in file {}: ", fileConfig.getFilename(), e);
                                throw new ConnectException(e);
                            }
                        }
                        log.debug("Skipped to offset {}", lastRecordedOffset);
                    }
                    streamOffset = (lastRecordedOffset != null) ? Long.valueOf(String.valueOf(lastRecordedOffset)) : 0L;
                } else {
                    log.info("positionInfo is null!");
                    streamOffset = 0L;
                }
                reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
                log.debug("Opened {} for reading", logFilename());
            } catch (NoSuchFileException e) {
                log.warn("Couldn't find file {} for FileStreamSourceTask, sleeping to wait for it to be created", logFilename());
                synchronized (this) {
                    try {
                        this.wait(1000);
                    } catch (InterruptedException e1) {
                        log.error("Interrupt error .", e1);
                    }
                }
                return null;
            } catch (IOException e) {
                log.error("Error while trying to open file {}: ", fileConfig.getFilename(), e);
                throw new ConnectException(e);
            }
        }

        try {
            final BufferedReader readerCopy;
            synchronized (this) {
                readerCopy = reader;
            }
            if (readerCopy == null) {
                return null;
            }

            List<ConnectRecord> records = null;

            int nread = 0;
            while (readerCopy.ready()) {
                nread = readerCopy.read(buffer, offset, buffer.length - offset);
                log.trace("Read {} bytes from {}", nread, logFilename());

                if (nread > 0) {
                    offset += nread;
                    if (offset == buffer.length) {
                        char[] newbuf = new char[buffer.length * 2];
                        System.arraycopy(buffer, 0, newbuf, 0, buffer.length);
                        buffer = newbuf;
                    }

                    String line;
                    do {
                        line = extractLine();
                        if (line != null) {
                            log.trace("Read a line from {}", logFilename());
                            if (records == null) {
                                records = new ArrayList<>();
                            }
                            List<Field> fields = new ArrayList<Field>();
                            Schema schema = new Schema(fileConfig.getFilename() + LINE, FieldType.STRING, fields);
                            final Field field = new Field(0, FileConstants.FILE_LINE_CONTENT, schema);
                            fields.add(field);
                            schema.setFields(fields);
                            ConnectRecord connectRecord = new ConnectRecord(offsetKey(fileConfig.getFilename()), offsetValue(streamOffset), System.currentTimeMillis(), schema, line);
                            connectRecord.addExtension("topic", fileConfig.getTopic());
                            records.add(connectRecord);
                            if (records.size() >= batchSize) {
                                return records;
                            }
                        }
                    }
                    while (line != null);
                }
            }

            if (nread <= 0) {
                synchronized (this) {
                    this.wait(1000);
                }
            }

            return records;
        } catch (IOException e) {
        } catch (InterruptedException e) {
            log.error("Interrupt error .", e);
        }
        return null;
    }

    private String extractLine() {
        int until = -1, newStart = -1;
        for (int i = 0; i < offset; i++) {
            if (buffer[i] == '\n') {
                until = i;
                newStart = i + 1;
                break;
            } else if (buffer[i] == '\r') {
                // We need to check for \r\n, so we must skip this if we can't check the next char
                if (i + 1 >= offset)
                    return null;

                until = i;
                newStart = (buffer[i + 1] == '\n') ? i + 2 : i + 1;
                break;
            }
        }

        if (until != -1) {
            String result = new String(buffer, 0, until);
            System.arraycopy(buffer, newStart, buffer, 0, buffer.length - newStart);
            offset = offset - newStart;
            if (streamOffset != null)
                streamOffset += newStart;
            return result;
        } else {
            return null;
        }
    }


    @Override public void start(SourceTaskContext sourceTaskContext) {
        this.sourceTaskContext = sourceTaskContext;
        fileConfig = new FileConfig();
        fileConfig.load(config);
        log.info("fileName is:{}", fileConfig.getFilename());
        if (fileConfig.getFilename() == null || fileConfig.getFilename().isEmpty()) {
            stream = System.in;
            streamOffset = null;
            reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
    }

    @Override public void validate(KeyValue config) {

    }

    @Override public void init(KeyValue config) {
        this.config = config;
    }

    @Override public void stop() {
        log.trace("Stopping");
        synchronized (this) {
            try {
                if (stream != null && stream != System.in) {
                    stream.close();
                    log.trace("Closed input stream");
                }
            } catch (IOException e) {
                log.error("Failed to close FileStreamSourceTask stream: ", e);
            }
            this.notify();
        }
    }

    @Override public void pause() {

    }

    @Override public void resume() {

    }

    private String logFilename() {
        return fileConfig.getFilename() == null ? "stdin" : fileConfig.getFilename();
    }

    private RecordPartition offsetKey(String filename) {
        Map<String, String> map = new HashMap<>();
        map.put(FILE_CONFIG, filename);
        RecordPartition recordPartition = new RecordPartition(map);
        return recordPartition;
    }

    private RecordOffset offsetValue(Long pos) {
        Map<String, String> map = new HashMap<>();
        map.put(FileConstants.NEXT_POSITION, String.valueOf(pos));
        RecordOffset recordOffset = new RecordOffset(map);
        return recordOffset;
    }

}
