/*******************************************************************************
 *    ___                  _   ____  ____
 *   / _ \ _   _  ___  ___| |_|  _ \| __ )
 *  | | | | | | |/ _ \/ __| __| | | |  _ \
 *  | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *   \__\_\\__,_|\___||___/\__|____/|____/
 *
 * Copyright (C) 2014-2017 Appsicle
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 ******************************************************************************/

package com.questdb.mon;

import com.questdb.JournalEntryWriter;
import com.questdb.JournalWriter;
import com.questdb.ex.JournalException;
import com.questdb.factory.Factory;
import com.questdb.factory.FactoryEventListener;
import com.questdb.factory.configuration.JournalStructure;
import com.questdb.iter.clock.Clock;
import com.questdb.log.Log;
import com.questdb.log.LogFactory;
import com.questdb.mp.MPSequence;
import com.questdb.mp.RingQueue;
import com.questdb.mp.SCSequence;
import com.questdb.mp.SynchronizedJob;
import com.questdb.std.ObjectFactory;

import java.io.Closeable;

public class FactoryEventLogger extends SynchronizedJob implements Closeable {
    private final static Log LOG = LogFactory.getLog(FactoryEventLogger.class);
    private final Factory factory;
    private final JournalWriter writer;
    private final RingQueue<FactoryEvent> eventQueue = new RingQueue<>(FactoryEvent.FACTORY, 16);
    private final MPSequence pubSeq = new MPSequence(eventQueue.getCapacity());
    private final SCSequence subSeq = new SCSequence();
    private final long commitBatchSize;
    private final long commitInterval;
    private final Clock clock;
    private long lastEventTimestamp = -1;

    public FactoryEventLogger(Factory factory, long commitBatchSize, long commitInterval, Clock clock) throws JournalException {
        this.factory = factory;
        this.commitBatchSize = commitBatchSize;
        this.commitInterval = commitInterval;
        this.clock = clock;
        this.writer = factory.writer(new JournalStructure("$mon_factory")
                .$byte("factoryType")
                .$long("thread")
                .$sym("name")
                .$short("event")
                .$ts()
                .$()
        );

        pubSeq.then(subSeq).then(pubSeq);

        this.factory.setEventListener(new FactoryEventListener() {
            @Override
            public boolean onEvent(byte factoryType, long thread, String name, short event) {
                long cursor = pubSeq.next();
                if (cursor < 0) {
                    return false;
                }

                FactoryEvent ev = eventQueue.get(cursor);
                ev.factoryType = factoryType;
                ev.thread = thread;
                ev.name = name;
                ev.event = event;

                pubSeq.done(cursor);
                return true;
            }
        });

        LOG.info().$("FactoryEventLogger started").$();
    }

    @Override
    public void close() {
        factory.setEventListener(null);
        writer.close();
        LOG.info().$("FactoryEventLogger stopped").$();
    }

    @Override
    protected boolean runSerially() {

        long cursor = subSeq.next();
        try {
            if (cursor < 0) {
                if (lastEventTimestamp > -1 && clock.getTicks() - lastEventTimestamp > commitInterval) {
                    lastEventTimestamp = -1;
                    writer.commit();
                }
                return false;
            }

            long available = subSeq.available();
            try {
                long count = available - cursor;

                while (cursor < available) {
                    FactoryEvent ev = eventQueue.get(cursor++);
                    JournalEntryWriter ew = writer.entryWriter(clock.getTicks());
                    ew.put(0, ev.factoryType);
                    ew.putLong(1, ev.thread);
                    ew.putSym(2, ev.name);
                    ew.putShort(3, ev.event);
                    ew.append();
                }

                if (count > commitBatchSize) {
                    writer.commit();
                }

                lastEventTimestamp = clock.getTicks();
            } finally {
                subSeq.done(available - 1);
            }

            return true;
        } catch (JournalException e) {
            LOG.error().$("Failed to log factory event: ").$(e).$();
            return false;
        }
    }

    private static class FactoryEvent {
        private final static ObjectFactory<FactoryEvent> FACTORY = new ObjectFactory<FactoryEvent>() {
            @Override
            public FactoryEvent newInstance() {
                return new FactoryEvent();
            }
        };
        private byte factoryType;
        private long thread;
        private String name;
        private short event;
    }
}
