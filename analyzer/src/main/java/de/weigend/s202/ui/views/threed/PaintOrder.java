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
package de.weigend.s202.ui.views.threed;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * The order in which boxes lying on one box are drawn: the one behind before
 * the one in front, so that the front one hides it.
 */
final class PaintOrder {

    private PaintOrder() {
    }

    /**
     * The boxes back to front. Two boxes that share a line of sight, that is
     * whose extents across the view overlap, are separated by a line along X
     * or along Y; the side of it that faces the viewer is in front. These
     * pairs give a partial order, completed by the distance of the middles.
     */
    static List<TopViewModel.Box> backToFront(List<TopViewModel.Box> boxes, Projection p) {
        if (boxes.size() < 2) {
            return boxes;
        }
        int n = boxes.size();
        double[] across0 = new double[n];
        double[] across1 = new double[n];
        double[] near = new double[n];
        for (int i = 0; i < n; i++) {
            TopViewModel.Box b = boxes.get(i);
            double mx = b.x() + b.w() / 2 - p.cx();
            double my = b.y() + b.h() / 2 - p.cy();
            double u = mx * p.cosTurn() - my * p.sinTurn();
            double half = (Math.abs(p.cosTurn()) * b.w() + Math.abs(p.sinTurn()) * b.h()) / 2;
            across0[i] = u - half;
            across1[i] = u + half;
            near[i] = mx * p.sinTurn() + my * p.cosTurn();
        }
        List<List<Integer>> behindOf = new ArrayList<>(n);
        int[] inFront = new int[n];
        for (int i = 0; i < n; i++) {
            behindOf.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (across1[i] <= across0[j] + 1e-9 || across1[j] <= across0[i] + 1e-9) {
                    continue;
                }
                int first = behind(boxes.get(i), boxes.get(j), p);
                if (first > 0) {
                    behindOf.get(i).add(j);
                    inFront[j]++;
                } else if (first < 0) {
                    behindOf.get(j).add(i);
                    inFront[i]++;
                }
            }
        }
        PriorityQueue<Integer> ready = new PriorityQueue<>((a, b) -> Double.compare(near[a], near[b]));
        for (int i = 0; i < n; i++) {
            if (inFront[i] == 0) {
                ready.add(i);
            }
        }
        boolean[] done = new boolean[n];
        List<TopViewModel.Box> result = new ArrayList<>(n);
        while (result.size() < n) {
            if (ready.isEmpty()) {
                // A cycle, which boxes that do not overlap cannot have: the nearest of the rest goes next.
                int next = -1;
                for (int i = 0; i < n; i++) {
                    if (!done[i] && (next < 0 || near[i] < near[next])) {
                        next = i;
                    }
                }
                inFront[next] = 0;
                ready.add(next);
            }
            int i = ready.poll();
            if (done[i]) {
                continue;
            }
            done[i] = true;
            result.add(boxes.get(i));
            for (int j : behindOf.get(i)) {
                if (--inFront[j] == 0 && !done[j]) {
                    ready.add(j);
                }
            }
        }
        return result;
    }

    /**
     * Which of two boxes on a common line of sight is behind: 1 if {@code a},
     * -1 if {@code b}, 0 if no axis separates them.
     */
    static int behind(TopViewModel.Box a, TopViewModel.Box b, Projection p) {
        double eps = 1e-6;
        // Seen along the view, X grows toward the viewer by sin(turn), Y by cos(turn).
        if (Math.abs(p.sinTurn()) > eps) {
            if (a.x() + a.w() <= b.x() + eps) {
                return p.sinTurn() > 0 ? 1 : -1;
            }
            if (b.x() + b.w() <= a.x() + eps) {
                return p.sinTurn() > 0 ? -1 : 1;
            }
        }
        if (Math.abs(p.cosTurn()) > eps) {
            if (a.y() + a.h() <= b.y() + eps) {
                return p.cosTurn() > 0 ? 1 : -1;
            }
            if (b.y() + b.h() <= a.y() + eps) {
                return p.cosTurn() > 0 ? -1 : 1;
            }
        }
        return 0;
    }
}
