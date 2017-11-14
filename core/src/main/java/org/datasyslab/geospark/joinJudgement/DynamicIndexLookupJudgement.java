/**
 * FILE: DynamicIndexLookupJudgement.java
 * PATH: org.datasyslab.geospark.joinJudgement.DynamicIndexLookupJudgement.java
 * Copyright (c) 2015-2017 GeoSpark Development Team
 * All rights reserved.
 */
package org.datasyslab.geospark.joinJudgement;

import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.index.SpatialIndex;
import com.vividsolutions.jts.index.quadtree.Quadtree;
import com.vividsolutions.jts.index.strtree.STRtree;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.spark.TaskContext;
import org.apache.spark.api.java.function.FlatMapFunction2;
import org.datasyslab.geospark.enums.IndexType;
import org.datasyslab.geospark.spatialOperator.JoinQuery.BuildSide;

import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import static org.datasyslab.geospark.spatialOperator.JoinQuery.BuildSide.BUILD_LEFT;
import static org.datasyslab.geospark.utils.TimeUtils.elapsedSince;

public class DynamicIndexLookupJudgement<T extends Geometry, U extends Geometry>
        extends JudgementBase
        implements FlatMapFunction2<Iterator<U>, Iterator<T>, Pair<U, T>>, Serializable {

    private static final Logger log = LogManager.getLogger(DynamicIndexLookupJudgement.class);

    private final IndexType indexType;
    private final BuildSide buildSide;

    /**
     * @see JudgementBase
     */
    public DynamicIndexLookupJudgement(boolean considerBoundaryIntersection,
                                       IndexType indexType,
                                       BuildSide buildSide,
                                       @Nullable DedupParams dedupParams) {
        super(considerBoundaryIntersection, dedupParams);
        this.indexType = indexType;
        this.buildSide = buildSide;
    }

    @Override
    public Iterator<Pair<U, T>> call(final Iterator<U> leftShapes, final Iterator<T> rightShapes) throws Exception {

        if (!leftShapes.hasNext() || !rightShapes.hasNext()) {
            return Collections.emptyIterator();
        }

        initPartition();

        final boolean buildLeft = (buildSide == BUILD_LEFT);

        final Iterator<? extends Geometry> buildShapes;
        final Iterator<? extends Geometry> streamShapes;
        if (buildLeft) {
            buildShapes = leftShapes;
            streamShapes = rightShapes;
        } else {
            buildShapes = rightShapes;
            streamShapes = leftShapes;
        }

        final SpatialIndex spatialIndex = buildIndex(buildShapes);

        return new Iterator<Pair<U, T>>() {
            // A batch of pre-computed matches
            private List<Pair<U, T>> batch = null;
            // An index of the element from 'batch' to return next
            private int nextIndex = 0;

            private int shapeCnt = 0;

            @Override
            public boolean hasNext() {
                if (batch != null) {
                    return true;
                } else {
                    return populateNextBatch();
                }
            }

            @Override
            public Pair<U, T> next() {
                if (batch == null) {
                    populateNextBatch();
                }

                if (batch != null) {
                    final Pair<U, T> result = batch.get(nextIndex);
                    nextIndex++;
                    if (nextIndex >= batch.size()) {
                        populateNextBatch();
                        nextIndex = 0;
                    }
                    return result;
                }

                throw new NoSuchElementException();
            }

            private boolean populateNextBatch() {
                if (!streamShapes.hasNext()) {
                    if (batch != null) {
                        batch = null;
                    }
                    return false;
                }

                batch = new ArrayList<>();

                while (streamShapes.hasNext()) {
                    shapeCnt ++;
                    final Geometry streamShape = streamShapes.next();
                    final List candidates = spatialIndex.query(streamShape.getEnvelopeInternal());
                    for (Object candidate : candidates) {
                        final Geometry buildShape = (Geometry) candidate;
                        if (buildLeft) {
                            if (match(buildShape, streamShape)) {
                                batch.add(Pair.of((U) buildShape, (T) streamShape));
                            }
                        } else {
                            if (match(streamShape, buildShape)) {
                                batch.add(Pair.of((U) streamShape, (T) buildShape));
                            }
                        }
                    }
                    logMilestone(shapeCnt, 100 * 1000, "Streaming shapes");
                    if (!batch.isEmpty()) {
                        return true;
                    }
                }

                batch = null;
                return false;
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    private SpatialIndex buildIndex(Iterator<? extends Geometry> geometries) {
        long startTime = System.currentTimeMillis();
        long count = 0;
        final SpatialIndex index = newIndex();
        while (geometries.hasNext()) {
            Geometry geometry = geometries.next();
            index.insert(geometry.getEnvelopeInternal(), geometry);
            count++;
        }
        index.query(new Envelope(0.0,0.0,0.0,0.0));
        log("Loaded %d shapes into an index in %d ms", count, elapsedSince(startTime));
        return index;
    }

    private SpatialIndex newIndex() {
        switch (indexType) {
            case RTREE:
                return new STRtree();
            case QUADTREE:
                return new Quadtree();
            default:
                throw new IllegalArgumentException("Unsupported index type: " + indexType);
        }
    }

    private void log(String message, Object...params) {
        if (Level.INFO.isGreaterOrEqual(log.getEffectiveLevel())) {
            final int partitionId = TaskContext.getPartitionId();
            final long threadId = Thread.currentThread().getId();
            log.info("[" + threadId + ", PID=" + partitionId + "] " + String.format(message, params));
        }
    }

    private void logMilestone(long cnt, long threshold, String name) {
        if (cnt > 1 && cnt % threshold == 1) {
            log("[%s] Reached a milestone: %d", name, cnt);
        }
    }
}