/*
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
package com.facebook.presto.elasticsearch;

import com.facebook.presto.spi.RecordCursor;
import com.facebook.presto.spi.type.Type;
import com.google.common.base.Strings;
import io.airlift.log.Logger;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHitField;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.BooleanType.BOOLEAN;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;
import static com.facebook.presto.spi.type.VarcharType.VARCHAR;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

public class ElasticsearchRecordCursor
        implements RecordCursor
{
    private static final Logger log = Logger.get(ElasticsearchRecordCursor.class);
    private final List<ElasticsearchColumnHandle> columnHandles;
    private final Map<String, Integer> jsonPathToIndex;
    private final Iterator<SearchHit> lines;
    private long totalBytes;
    private List<String> fields;

    public ElasticsearchRecordCursor(List<ElasticsearchColumnHandle> columnHandles, ElasticsearchTableSource tableSource, ElasticsearchClient elasticsearchClient)
    {
        this.columnHandles = columnHandles;
        this.jsonPathToIndex = new HashMap();
        this.totalBytes = 0;
        ArrayList<String> fieldsNeeded = new ArrayList();

        for (int i = 0; i < columnHandles.size(); i++) {
            this.jsonPathToIndex.put(columnHandles.get(i).getColumnJsonPath(), i);
            fieldsNeeded.add(columnHandles.get(i).getColumnJsonPath());
        }

        this.lines = getRows(tableSource, fieldsNeeded, elasticsearchClient).iterator();
    }

    @Override
    public long getTotalBytes()
    {
        return totalBytes;
    }

    @Override
    public long getCompletedBytes()
    {
        return totalBytes;
    }

    @Override
    public long getReadTimeNanos()
    {
        return 0;
    }

    @Override
    public Type getType(int field)
    {
        checkArgument(field < columnHandles.size(), "Invalid field index");
        return columnHandles.get(field).getColumnType();
    }

    @Override
    public boolean advanceNextPosition()
    {
        if (!lines.hasNext()) {
            return false;
        }
        SearchHit hit = lines.next();

        fields = new ArrayList(Collections.nCopies(columnHandles.size(), "-1"));

        Map<String, SearchHitField> map = hit.getFields();
        for (Map.Entry<String, SearchHitField> entry : map.entrySet()) {
            String jsonPath = entry.getKey().toString();
            SearchHitField entryValue = entry.getValue();

            // we get the value, wrapped in a list (of size 1 of course) -> [value] (The java api returns in this way)
            ArrayList<Object> lis = new ArrayList(entryValue.getValues());
            String value = String.valueOf(lis.get(0));

            fields.set(jsonPathToIndex.get(jsonPath), value);
        }

        totalBytes += fields.size();

        return true;
    }

    @Override
    public boolean getBoolean(int field)
    {
        checkFieldType(field, BOOLEAN);
        return Boolean.parseBoolean(getFieldValue(field));
    }

    @Override
    public long getLong(int field)
    {
        checkFieldType(field, BIGINT);
        return Long.parseLong(getFieldValue(field));
    }

    @Override
    public double getDouble(int field)
    {
        checkFieldType(field, DOUBLE);
        return Double.parseDouble(getFieldValue(field));
    }

    @Override
    public Slice getSlice(int field)
    {
        checkFieldType(field, VARCHAR);
        return Slices.utf8Slice(getFieldValue(field));
    }

    @Override
    public Object getObject(int field)
    {
        return null;
    }

    @Override
    public boolean isNull(int field)
    {
        checkArgument(field < columnHandles.size(), "Invalid field index");
        return Strings.isNullOrEmpty(getFieldValue(field));
    }

    private void checkFieldType(int field, Type expected)
    {
        Type actual = getType(field);
        checkArgument(actual.equals(expected), "Expected field %s to be type %s but is %s", field, expected, actual);
    }

    @Override
    public void close()
    {
    }

    String[] getIndices(Client client, String type)
    {
        return Arrays.asList(client
                .admin()
                .cluster()
                .prepareState()
                .execute()
                .actionGet()
                .getState()
                .getMetaData()
                .concreteAllIndices())
                .stream()
                .filter(e -> e.startsWith(type.concat("_")))
                .toArray(size -> new String[size]);
    }

    List<SearchHit> getRows(ElasticsearchTableSource tableSource, ArrayList<String> fieldsNeeded, ElasticsearchClient elasticsearchClient)
    {
        List<SearchHit> result = new ArrayList<>();
        String clusterName = tableSource.getClusterName();
        String hostAddress = tableSource.getHostAddress();
        int port = tableSource.getPort();
        String index = tableSource.getIndex();
        String type = tableSource.getType();

        log.debug(String.format("Connecting to cluster %s from %s:%d, index %s, type %s", clusterName, hostAddress, port, index, type));
        Client client = elasticsearchClient.getInternalClients().get(clusterName);
        SearchResponse scrollResp = client
                .prepareSearch(getIndices(client, type))
                .setTypes(tableSource.getType())
                .addFields(fieldsNeeded.toArray(new String[fieldsNeeded.size()]))
                .setSearchType(SearchType.SCAN)
                .setScroll(new TimeValue(60000))
                .setSize(20000).execute()
                .actionGet(); //20000 hits per shard will be returned for each scroll

        //Scroll until no hits are returned
        while (true) {
            for (SearchHit hit : scrollResp.getHits().getHits()) {
                result.add(hit);
            }

            scrollResp = client
                    .prepareSearchScroll(scrollResp.getScrollId())
                    .setScroll(new TimeValue(600000)).execute().actionGet();

            if (scrollResp.getHits().getHits().length == 0) {
                break;
            }
        }

        return result;
    }

    String getFieldValue(int field)
    {
        checkState(fields != null, "Cursor has not been advanced yet");
        return fields.get(field);
    }
}
