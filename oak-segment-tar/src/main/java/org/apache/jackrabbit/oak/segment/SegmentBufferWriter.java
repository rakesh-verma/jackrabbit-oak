/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.jackrabbit.oak.segment;

import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Maps.newLinkedHashMap;
import static com.google.common.collect.Sets.newHashSet;
import static java.lang.System.arraycopy;
import static java.lang.System.currentTimeMillis;
import static java.lang.System.identityHashCode;
import static org.apache.jackrabbit.oak.segment.Segment.GC_GENERATION_OFFSET;
import static org.apache.jackrabbit.oak.segment.Segment.HEADER_SIZE;
import static org.apache.jackrabbit.oak.segment.Segment.MAX_SEGMENT_SIZE;
import static org.apache.jackrabbit.oak.segment.Segment.RECORD_ID_BYTES;
import static org.apache.jackrabbit.oak.segment.Segment.align;
import static org.apache.jackrabbit.oak.segment.SegmentId.isDataSegmentId;
import static org.apache.jackrabbit.oak.segment.SegmentVersion.LATEST_VERSION;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class encapsulates the state of a segment being written. It provides methods
 * for writing primitive data types and for pre-allocating buffer space in the current
 * segment. Should the current segment not have enough space left the current segment
 * is flushed and a fresh one is allocated.
 * <p>
 * The common usage pattern is:
 * <pre>
 *    SegmentBufferWriter writer = ...
 *    writer.prepare(...)  // allocate buffer
 *    writer.writeXYZ(...)
 * </pre>
 * The behaviour of this class is undefined should the pre-allocated buffer be
 * overrun be calling any of the write methods.
 * <p>
 * Instances of this class are <em>not thread safe</em>. See also the class comment of
 * {@link SegmentWriter}.
 */
public class SegmentBufferWriter implements WriteOperationHandler {

    private static final Logger LOG = LoggerFactory.getLogger(SegmentBufferWriter.class);

    private static final boolean DISABLE_GENERATION_CHECK = Boolean.getBoolean("disable-generation-check");

    private static final class Statistics {

        int segmentIdCount;

        int recordIdCount;

        int recordCount;

        int size;

        SegmentId id;

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();

            builder.append("id=").append(id);
            builder.append(",");
            builder.append("size=").append(size);
            builder.append(",");
            builder.append("segmentIdCount=").append(segmentIdCount);
            builder.append(",");
            builder.append("recordIdCount=").append(recordIdCount);
            builder.append(",");
            builder.append("recordCount=").append(recordCount);

            return builder.toString();
        }
    }

    /**
     * The set of root records (i.e. ones not referenced by other records)
     * in this segment.
     */
    private final Map<RecordId, RecordType> roots = newLinkedHashMap();

    private final Set<SegmentId> referencedSegmentIds = newHashSet();

    @Nonnull
    private final SegmentStore store;

    @Nonnull
    private final SegmentTracker tracker;

    @Nonnull
    private final SegmentReader reader;

    /**
     * Id of this writer.
     */
    @Nonnull
    private final String wid;

    private final int generation;

    /**
     * The segment write buffer, filled from the end to the beginning
     * (see OAK-629).
     */
    private byte[] buffer;

    private Segment segment;

    /**
     * The number of bytes already written (or allocated). Counted from
     * the <em>end</em> of the buffer.
     */
    private int length;

    /**
     * Current write position within the buffer. Grows up when raw data
     * is written, but shifted downwards by the prepare methods.
     */
    private int position;

    private Statistics statistics;

    /**
     * Mark this buffer as dirty. A dirty buffer needs to be flushed to disk
     * regularly to avoid data loss.
     */
    private boolean dirty;

    public SegmentBufferWriter(@Nonnull SegmentStore store,
                               @Nonnull SegmentTracker tracker,
                               @Nonnull SegmentReader reader,
                               @CheckForNull String wid,
                               int generation) {
        this.store = checkNotNull(store);
        this.tracker = checkNotNull(tracker);
        this.reader = checkNotNull(reader);
        this.wid = (wid == null
                ? "w-" + identityHashCode(this)
                : wid);

        this.generation = generation;
        this.statistics = new Statistics();
        newSegment();
    }

    @Override
    public RecordId execute(WriteOperation writeOperation) throws IOException {
        return writeOperation.execute(this);
    }

    int getGeneration() {
        return generation;
    }

    /**
     * Allocate a new segment and write the segment meta data.
     * The segment meta data is a string of the format {@code "{wid=W,sno=S,t=T}"}
     * where:
     * <ul>
     * <li>{@code W} is the writer id {@code wid}, </li>
     * <li>{@code S} is a unique, increasing sequence number corresponding to the allocation order
     * of the segments in this store, </li>
     * <li>{@code T} is a time stamp according to {@link System#currentTimeMillis()}.</li>
     * </ul>
     * The segment meta data is guaranteed to be the first string record in a segment.
     */
    private void newSegment() {
        buffer = new byte[Segment.MAX_SEGMENT_SIZE];
        buffer[0] = '0';
        buffer[1] = 'a';
        buffer[2] = 'K';
        buffer[3] = SegmentVersion.asByte(LATEST_VERSION);
        buffer[4] = 0; // reserved
        buffer[5] = 0; // refcount

        buffer[GC_GENERATION_OFFSET] = (byte) (generation >> 24);
        buffer[GC_GENERATION_OFFSET + 1] = (byte) (generation >> 16);
        buffer[GC_GENERATION_OFFSET + 2] = (byte) (generation >> 8);
        buffer[GC_GENERATION_OFFSET + 3] = (byte) generation;
        length = 0;
        position = buffer.length;
        roots.clear();
        referencedSegmentIds.clear();

        String metaInfo =
            "{\"wid\":\"" + wid + '"' +
            ",\"sno\":" + tracker.getSegmentCount() +
            ",\"t\":" + currentTimeMillis() + "}";
        try {
            segment = new Segment(store, reader, buffer, metaInfo);

            statistics = new Statistics();
            statistics.id = segment.getSegmentId();

            byte[] data = metaInfo.getBytes(UTF_8);
            RecordWriters.newValueWriter(data.length, data).write(this);
        } catch (IOException e) {
            LOG.error("Unable to write meta info to segment {} {}", segment.getSegmentId(), metaInfo, e);
        }

        dirty = false;
    }

    public void writeByte(byte value) {
        position = BinaryUtils.writeByte(buffer, position, value);
        dirty = true;
    }

    public void writeShort(short value) {
        position = BinaryUtils.writeShort(buffer, position, value);
        dirty = true;
    }

    public void writeInt(int value) {
        position = BinaryUtils.writeInt(buffer, position, value);
        dirty = true;
    }

    public void writeLong(long value) {
        position = BinaryUtils.writeLong(buffer, position, value);
        dirty = true;
    }

    /**
     * Write a record id, and marks the record id as referenced (removes it from
     * the unreferenced set).
     *
     * @param recordId the record id
     */
    public void writeRecordId(RecordId recordId) {
        writeRecordId(recordId, true);
    }

    /**
     * Write a record ID. Optionally, mark this record ID as being a reference.
     * If a record ID is marked as a reference, the referenced record can't be a
     * root record in this segment.
     *
     * @param recordId  the record ID.
     * @param reference {@code true} if this record ID is a reference, {@code
     *                  false} otherwise.
     */
    public void writeRecordId(RecordId recordId, boolean reference) {
        checkNotNull(recordId);

        if (reference) {
            roots.remove(recordId);
        }

        int offset = recordId.getOffset();

        checkState(0 <= offset && offset < MAX_SEGMENT_SIZE);
        checkState(offset == align(offset, 1 << Segment.RECORD_ALIGN_BITS));

        long msb = recordId.getSegmentId().getMostSignificantBits();
        long lsb = recordId.getSegmentId().getLeastSignificantBits();

        writeLong(msb);
        writeLong(lsb);
        writeShort((short)((offset >> Segment.RECORD_ALIGN_BITS) & 0xffff));

        if (!recordId.getSegmentId().equals(segment.getSegmentId())) {
            referencedSegmentIds.add(recordId.getSegmentId());
        }

        statistics.recordIdCount++;

        dirty = true;
    }

    // FIXME OAK-4287: Disable / remove SegmentBufferWriter#checkGCGeneration
    // Disable/remove this in production
    private void checkGCGeneration(SegmentId id) {
        if (!DISABLE_GENERATION_CHECK) {
            try {
                if (isDataSegmentId(id.getLeastSignificantBits())) {
                    if (id.getGcGeneration() < generation) {
                        LOG.warn("Detected reference from {} to segment {} from a previous gc generation.",
                                info(this.segment), info(id.getSegment()), new Exception());
                    }
                }
            } catch (SegmentNotFoundException snfe) {
                LOG.warn("Detected reference from {} to non existing segment {}",
                        info(this.segment), id, snfe);
            }
        }
    }

    private static String info(Segment segment) {
        String info = segment.getSegmentId().toString();
        if (isDataSegmentId(segment.getSegmentId().getLeastSignificantBits())) {
            info += (" " + segment.getSegmentInfo());
        }
        return info;
    }

    public void writeBytes(byte[] data, int offset, int length) {
        arraycopy(data, offset, buffer, position, length);
        position += length;
        dirty = true;
    }

    /**
     * Adds a segment header to the buffer and writes a segment to the segment
     * store. This is done automatically (called from prepare) when there is not
     * enough space for a record. It can also be called explicitly.
     */
    @Override
    public void flush() throws IOException {
        if (dirty) {
            int rootcount = roots.size();
            BinaryUtils.writeShort(buffer, Segment.ROOT_COUNT_OFFSET, (short) rootcount);

            int referencedSegmentIdCount = referencedSegmentIds.size();
            BinaryUtils.writeInt(buffer, Segment.REFERENCED_SEGMENT_ID_COUNT_OFFSET, referencedSegmentIdCount);
            statistics.segmentIdCount = referencedSegmentIdCount;

            int totalLength = align(HEADER_SIZE + referencedSegmentIdCount * 16 + rootcount * 3 + length, 16);

            if (totalLength > buffer.length) {
                throw new IllegalStateException("too much data for a segment");
            }

            statistics.size = length = totalLength;

            int pos = HEADER_SIZE;
            if (pos + length <= buffer.length) {
                // the whole segment fits to the space *after* the referenced
                // segment identifiers we've already written, so we can safely
                // copy those bits ahead even if concurrent code is still
                // reading from that part of the buffer
                arraycopy(buffer, 0, buffer, buffer.length - length, pos);
                pos += buffer.length - length;
            } else {
                // this might leave some empty space between the header and
                // the record data, but this case only occurs when the
                // segment is >252kB in size and the maximum overhead is <<4kB,
                // which is acceptable
                length = buffer.length;
            }

            for (SegmentId id : referencedSegmentIds) {
                pos = BinaryUtils.writeLong(buffer, pos, id.getMostSignificantBits());
                pos = BinaryUtils.writeLong(buffer, pos, id.getLeastSignificantBits());
            }

            for (Map.Entry<RecordId, RecordType> entry : roots.entrySet()) {
                pos = BinaryUtils.writeByte(buffer, pos, (byte) entry.getValue().ordinal());
                pos = BinaryUtils.writeShort(buffer, pos, (short) (entry.getKey().getOffset() >> Segment.RECORD_ALIGN_BITS));
            }

            SegmentId segmentId = segment.getSegmentId();
            LOG.debug("Writing data segment: {} ", statistics);
            store.writeSegment(segmentId, buffer, buffer.length - length, length);
            newSegment();
        }
    }

    /**
     * Before writing a record (which are written backwards, from the end of the
     * file to the beginning), this method is called, to ensure there is enough
     * space. A new segment is also created if there is not enough space in the
     * segment lookup table or elsewhere.
     * <p>
     * This method does not actually write into the segment, just allocates the
     * space (flushing the segment if needed and starting a new one), and sets
     * the write position (records are written from the end to the beginning,
     * but within a record from left to right).
     *
     * @param type the record type (only used for root records)
     * @param size the size of the record, excluding the size used for the
     *            record ids
     * @param ids the record ids
     * @return a new record id
     */
    public RecordId prepare(RecordType type, int size, Collection<RecordId> ids) throws IOException {
        checkArgument(size >= 0);
        checkNotNull(ids);

        int idCount = ids.size();
        int recordSize = align(size + idCount * RECORD_ID_BYTES, 1 << Segment.RECORD_ALIGN_BITS);

        // First compute the header and segment sizes based on the assumption
        // that *all* identifiers stored in this record point to previously
        // unreferenced segments.

        int rootCount = roots.size() + 1;
        int referencedIdCount = referencedSegmentIds.size() + ids.size();
        int headerSize = HEADER_SIZE + rootCount * 3 + referencedIdCount * 16;
        int segmentSize = align(headerSize + recordSize + length, 16);

        // If the size estimate looks too big, recompute it with a more
        // accurate refCount value. We skip doing this when possible to
        // avoid the somewhat expensive list and set traversals.

        if (segmentSize > buffer.length) {

            // The set of old record ids in this segment
            // that were previously root record ids, but will no longer be,
            // because the record to be written references them.
            // This needs to be a set, because the list of ids can
            // potentially reference the same record multiple times

            Set<SegmentId> segmentIds = newHashSet();
            Set<RecordId> notRoots = newHashSet();

            for (RecordId recordId : ids) {
                SegmentId segmentId = recordId.getSegmentId();
                if (!(segmentId.equals(segment.getSegmentId()))) {
                    segmentIds.add(segmentId);
                } else if (roots.containsKey(recordId)) {
                    notRoots.add(recordId);
                }
            }
            rootCount -= notRoots.size();

            // Adjust the estimation of the new referenced segment ID count.

            for (SegmentId segmentId : segmentIds) {
                if (referencedSegmentIds.contains(segmentId.asUUID())) {
                    referencedIdCount--;
                }
            }

            headerSize = HEADER_SIZE + rootCount * 3 + referencedIdCount * 16;
            segmentSize = align(headerSize + recordSize + length, 16);
        }

        if (segmentSize > buffer.length || rootCount > 0xffff) {
            flush();
        }

        statistics.recordCount++;

        length += recordSize;
        position = buffer.length - length;
        checkState(position >= 0);

        RecordId id = new RecordId(segment.getSegmentId(), position);
        roots.put(id, type);
        return id;
    }

}
