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

package org.apache.jackrabbit.oak.plugins.segment;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.apache.jackrabbit.oak.plugins.segment.RecordIdSet.ShortSet;
import org.junit.Test;

public class ShortSetTest {
    private final ShortSet set = new ShortSet();

    @Test
    public void empty() {
        for (short k = Short.MIN_VALUE; k < Short.MAX_VALUE; k++) {
            assertFalse(set.contains(k));
        }
    }

    @Test
    public void addOne() {
        set.add(s(42));
        assertTrue(set.contains(s(42)));
    }

    @Test
    public void addTwo() {
        set.add(s(21));
        set.add(s(42));
        assertTrue(set.contains(s(21)));
        assertTrue(set.contains(s(42)));
    }

    @Test
    public void addTwoReverse() {
        set.add(s(42));
        set.add(s(21));
        assertTrue(set.contains(s(21)));
        assertTrue(set.contains(s(42)));
    }

    @Test
    public void addFirst() {
        short[] elements = new short[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 0};
        addAndCheck(elements);
    }

    @Test
    public void addLast() {
        short[] elements = new short[]{8, 7, 6, 5, 4, 3, 2, 1, 0, 9};
        addAndCheck(elements);
    }

    @Test
    public void addMedian() {
        short[] elements = new short[]{0, 1, 2, 3, 4, 6, 7, 8, 9, 5};
        addAndCheck(elements);
    }

    @Test
    public void addRandom() {
        short[] elements = new short[8192];
        Random rnd = new Random();
        for (int k = 0; k < elements.length; k++) {
            elements[k] = s(rnd.nextInt(1 + Short.MAX_VALUE - Short.MIN_VALUE) + Short.MIN_VALUE);
        }

        addAndCheck(elements);
    }

    private void addAndCheck(short[] elements) {
        for (short k : elements) {
            set.add(k);
        }
        for (short k : elements) {
            assertTrue(set.contains(k));
        }
    }

    private static short s(int n) {
        return (short) n;
    }
}
