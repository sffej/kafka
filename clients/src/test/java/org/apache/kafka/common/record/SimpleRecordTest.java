/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.common.record;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Iterator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SimpleRecordTest {

    /* This scenario can happen if the record size field is corrupt and we end up allocating a buffer that is too small */
    @Test(expected = InvalidRecordException.class)
    public void testIsValidWithTooSmallBuffer() {
        ByteBuffer buffer = ByteBuffer.allocate(2);
        Record record = new Record(buffer);
        assertFalse(record.isValid());
        record.ensureValid();
    }

    @Test(expected = InvalidRecordException.class)
    public void testIsValidWithChecksumMismatch() {
        ByteBuffer buffer = ByteBuffer.allocate(4);
        // set checksum
        buffer.putInt(2);
        Record record = new Record(buffer);
        assertFalse(record.isValid());
        record.ensureValid();
    }

    @Test
    public void buildEosRecord() {
        ByteBuffer buffer = ByteBuffer.allocate(2048);

        MemoryLogBufferBuilder builder = MemoryLogBuffer.builder(buffer, Record.MAGIC_VALUE_V2, CompressionType.NONE, TimestampType.CREATE_TIME, 1234567L);
        builder.append(1234567, System.currentTimeMillis(), "a".getBytes(), "v".getBytes());
        builder.append(1234568, System.currentTimeMillis(), "b".getBytes(), "v".getBytes());

        MemoryLogBuffer logBuffer = builder.build();
        Iterator<LogEntry.ShallowLogEntry> shallowEntries = logBuffer.shallowIterator();
        while (shallowEntries.hasNext()) {
            LogEntry.ShallowLogEntry entry = shallowEntries.next();
            assertEquals(1234567, entry.firstOffset());
            assertEquals(1234568, entry.lastOffset());
            assertTrue(entry.isValid());

            for (LogRecord record : entry) {
                assertTrue(record.isValid());
            }
        }
    }
}
