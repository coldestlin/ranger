/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ranger.plugin.util;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;

public class AutoClosableLock implements AutoCloseable {
    private final Lock lock;

    public AutoClosableLock(Lock lock) {
        this.lock = lock;

        this.lock.lock();
    }

    @Override
    public void close() {
        lock.unlock();
    }

    public static class AutoClosableTryLock implements AutoCloseable {
        private final Lock    lock;
        private final boolean isLocked;

        public AutoClosableTryLock(Lock lock, long timeout, TimeUnit timeUnit) {
            this.lock = lock;

            boolean isLocked = false;

            try {
                isLocked = this.lock.tryLock(timeout, timeUnit);
            } catch (InterruptedException excp) {
                // ignored
            }

            this.isLocked = isLocked;
        }

        public boolean isLocked() { return isLocked; }

        @Override
        public void close() {
            if (isLocked) {
                lock.unlock();
            }
        }
    }

    public static class AutoClosableReadLock implements AutoCloseable {
        private final ReadWriteLock lock;

        public AutoClosableReadLock(ReadWriteLock lock) {
            this.lock = lock;

            this.lock.readLock().lock();
        }

        @Override
        public void close() {
            lock.readLock().unlock();
        }
    }

    public static class AutoClosableWriteLock implements AutoCloseable {
        private final ReadWriteLock lock;

        public AutoClosableWriteLock(ReadWriteLock lock) {
            this.lock = lock;

            this.lock.writeLock().lock();
        }

        @Override
        public void close() {
            lock.writeLock().unlock();
        }
    }

    public static class AutoClosableTryWriteLock implements AutoCloseable {
        private final ReadWriteLock lock;
        private final boolean       isLocked;

        public AutoClosableTryWriteLock(ReadWriteLock lock) {
            this.lock     = lock;
            this.isLocked = this.lock.writeLock().tryLock();
        }

        public boolean isLocked() { return isLocked; }

        @Override
        public void close() {
            if (isLocked) {
                lock.writeLock().unlock();
            }
        }
    }
}
