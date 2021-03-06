/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2020 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.griffin.engine.table;

import io.questdb.cairo.sql.*;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.std.Misc;
import org.jetbrains.annotations.Nullable;

public class DataFrameRecordCursorFactory extends AbstractDataFrameRecordCursorFactory {
    private final DataFrameRecordCursor cursor;
    private final boolean followsOrderByAdvice;
    private final Function filter;

    public DataFrameRecordCursorFactory(
            RecordMetadata metadata,
            DataFrameCursorFactory dataFrameCursorFactory,
            RowCursorFactory rowCursorFactory,
            boolean followsOrderByAdvice,
            // filter included here only for lifecycle management of the latter
            @Nullable Function filter
    ) {
        super(metadata, dataFrameCursorFactory);
        this.cursor = new DataFrameRecordCursor(rowCursorFactory, rowCursorFactory.isEntity(), filter);
        this.followsOrderByAdvice = followsOrderByAdvice;
        this.filter = filter;
    }

    @Override
    public boolean followedOrderByAdvice() {
        return followsOrderByAdvice;
    }

    @Override
    public boolean recordCursorSupportsRandomAccess() {
        return true;
    }

    @Override
    protected RecordCursor getCursorInstance(
            DataFrameCursor dataFrameCursor,
            SqlExecutionContext executionContext
    ) {
        cursor.of(dataFrameCursor, executionContext);
        if (filter != null) {
            filter.init(cursor, executionContext);
        }
        return cursor;
    }

    @Override
    public void close() {
        Misc.free(filter);
    }
}
