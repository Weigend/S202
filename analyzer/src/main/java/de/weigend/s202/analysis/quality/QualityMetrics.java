/*
 * Copyright 2026 Weigend AM GmbH & Co.KG
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.weigend.s202.analysis.quality;

/**
 * What is measured for the whole of an analysis or a
 * part of it. <b>Fat</b> and <b>tangled</b> are normalised to {@code [0, 1]},
 * where 0 is healthy (green) and 1 is unhealthy (red):
 *
 * <ul>
 *   <li><b>fat</b> — the number of classes a class depends on, on average;
 *       {@value #FAT_SATURATION} or more are 1.</li>
 *   <li><b>tangled</b> — the share of these dependencies that lie on a class
 *       cycle. 0 means cycle-free, 1 means everything is in cycles.</li>
 *   <li><b>breakout</b> — how many dependencies there are between the direct
 *       members of the container: how hard it is to understand at one look.
 *       Unlike the other two it is no average and depends on where the
 *       classes sit.</li>
 * </ul>
 */
public final class QualityMetrics {

    /** Average dependencies per class at which fat is 1. */
    static final double FAT_SATURATION = 10.0;
    /** The breakout of something that is not one container. */
    public static final int NO_BREAKOUT = -1;

    private final int classCount;
    private final int dependencyCount;
    private final int intraSccDependencyCount;
    private final int breakout;

    QualityMetrics(int classCount, int dependencyCount, int intraSccDependencyCount, int breakout) {
        this.classCount = classCount;
        this.dependencyCount = dependencyCount;
        this.intraSccDependencyCount = intraSccDependencyCount;
        this.breakout = breakout;
    }

    public double getFat() {
        return Math.min(1.0, getAverageDependencies() / FAT_SATURATION);
    }

    /** The average behind {@link #getFat()}, not limited. */
    public double getAverageDependencies() {
        return classCount == 0 ? 0 : (double) dependencyCount / classCount;
    }

    public double getTangled() {
        return dependencyCount == 0 ? 0 : (double) intraSccDependencyCount / dependencyCount;
    }

    /** {@link #NO_BREAKOUT} for something that is not one container. */
    public int getBreakout() { return breakout; }

    public int getClassCount() { return classCount; }
    public int getDependencyCount() { return dependencyCount; }
    public int getIntraSccDependencyCount() { return intraSccDependencyCount; }

    @Override
    public String toString() {
        return String.format("QualityMetrics{fat=%.3f, tangled=%.3f, breakout=%d, classes=%d, deps=%d, intraScc=%d}",
                getFat(), getTangled(), breakout, classCount, dependencyCount, intraSccDependencyCount);
    }
}
