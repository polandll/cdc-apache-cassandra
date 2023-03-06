/**
 * Copyright DataStax, Inc 2021.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.datastax.oss.cdc.backfill;

import com.datastax.oss.cdc.agent.AbstractMutation;
import com.datastax.oss.cdc.agent.PulsarMutationSender;
import com.datastax.oss.cdc.backfill.exporter.ExportedTable;
import com.datastax.oss.cdc.backfill.factory.ConnectorFactory;
import com.datastax.oss.cdc.backfill.factory.PulsarMutationSenderFactory;
import com.datastax.oss.cdc.backfill.importer.PulsarImporter;
import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.type.DataTypes;
import com.datastax.oss.driver.internal.core.metadata.schema.DefaultColumnMetadata;
import com.datastax.oss.dsbulk.tests.utils.StringUtils;
import org.apache.cassandra.cql3.ColumnIdentifier;
import org.apache.cassandra.db.marshal.BooleanType;
import org.apache.cassandra.db.marshal.BytesType;
import org.apache.cassandra.db.marshal.IntegerType;
import org.apache.cassandra.db.marshal.SimpleDateType;
import org.apache.cassandra.db.marshal.TimeType;
import org.apache.cassandra.db.marshal.UTF8Type;
import org.apache.cassandra.schema.ColumnMetadata;
import org.apache.cassandra.schema.TableMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class PulsarImporterTest {
    @Mock
    private TableMetadata tableMetadata;

    @Mock
    private ExportedTable exportedTable;

    @Mock
    private PulsarMutationSenderFactory factory;

    @Mock
    private PulsarMutationSender sender;

    @Captor
    private ArgumentCaptor<AbstractMutation<TableMetadata>> abstractMutationCaptor;
    private PulsarImporter importer;

    @BeforeEach
    public void init() {
        MockitoAnnotations.openMocks(this);

        Mockito.when(sender.sendMutationAsync(Mockito.any())).thenReturn(CompletableFuture.completedFuture(null));
        Mockito.when(factory.newPulsarMutationSender()).thenReturn(sender);
    }

    @Test
    public void testImportPartitionKeyOnly() {
        // given
        String fileName = "sample-001.csv";
        ConnectorFactory connectorFactory = new ConnectorFactory(Paths.get(url(fileName)));
        importer = new PulsarImporter(connectorFactory, exportedTable, factory);

        List<ColumnMetadata> cassandraColumns = new ArrayList<>();
        ColumnIdentifier identifier = new ColumnIdentifier("key", true);
        ColumnMetadata columnMetadata = new ColumnMetadata("ks1", "key", identifier, UTF8Type.instance, 0, ColumnMetadata.Kind.PARTITION_KEY);
        cassandraColumns.add(columnMetadata);
        Mockito.when(tableMetadata.primaryKeyColumns()).thenReturn(cassandraColumns);

        Mockito.when(exportedTable.getCassandraTable()).thenReturn(tableMetadata);
        List<com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata > columns = new ArrayList<>();
        columns.add(new DefaultColumnMetadata(CqlIdentifier.fromInternal("ks1"),CqlIdentifier.fromInternal("table1"), CqlIdentifier.fromInternal("key"), DataTypes.ASCII, false));Mockito.when(exportedTable.getPrimaryKey()).thenReturn(columns);

        // when
        ExitStatus status = importer.importTable();

        // then
        assertEquals(ExitStatus.STATUS_OK, status);
        Mockito.verify(sender, Mockito.times(2)).sendMutationAsync(abstractMutationCaptor.capture());
        List<AbstractMutation<TableMetadata>> pkValues = abstractMutationCaptor.getAllValues();
        assertEquals(2, pkValues.size());
        List<Object> allPkValues = pkValues.stream().flatMap(v-> Arrays.stream(v.getPkValues())).collect(Collectors.toList());
        assertThat(allPkValues, containsInAnyOrder("id3", "id8"));
    }

    @Test
    public void testImportPartitionAndClusteringKeys() {
        // given
        String fileName = "sample-002.csv";
        ConnectorFactory connectorFactory = new ConnectorFactory(Paths.get(url(fileName)));
        importer = new PulsarImporter(connectorFactory, exportedTable, factory);

        List<ColumnMetadata> cassandraColumns = new ArrayList<>();
        ColumnIdentifier xtextIdentifier =
                new ColumnIdentifier("xtext", true);
        ColumnMetadata xtextColumnMetadata =
                new ColumnMetadata("ks1", "xtext", xtextIdentifier, UTF8Type.instance, 0, ColumnMetadata.Kind.PARTITION_KEY);
        ColumnIdentifier xbooleanIdentifier =
                new ColumnIdentifier("xboolean", true);
        ColumnMetadata xbooleanColumnMetadata =
                new ColumnMetadata("ks1", "xboolean", xbooleanIdentifier, BooleanType.instance, 1, ColumnMetadata.Kind.CLUSTERING);
        ColumnIdentifier xintIdentifier =
                new ColumnIdentifier("xint", true);
        ColumnIdentifier xtimeIdentifier =
                new ColumnIdentifier("xtime", true);
        ColumnIdentifier xdateIdentifier =
                new ColumnIdentifier("xdate", true);
        ColumnIdentifier xblobIdentifier =
                new ColumnIdentifier("xblob", true);
        ColumnMetadata xintColumnMetadata =
                new ColumnMetadata("ks1", "xint", xintIdentifier, IntegerType.instance, 2, ColumnMetadata.Kind.CLUSTERING);
        ColumnMetadata xtimeColumnMetadata =
                new ColumnMetadata("ks1", "xtime", xtimeIdentifier, TimeType.instance, 3, ColumnMetadata.Kind.CLUSTERING);
        ColumnMetadata xdateColumnMetadata =
                new ColumnMetadata("ks1", "xdate", xdateIdentifier, SimpleDateType.instance, 4, ColumnMetadata.Kind.CLUSTERING);
        ColumnMetadata xblobColumnMetadata =
                new ColumnMetadata("ks1", "xblob", xblobIdentifier, BytesType.instance, 5, ColumnMetadata.Kind.CLUSTERING);
        cassandraColumns.add(xtextColumnMetadata);
        cassandraColumns.add(xbooleanColumnMetadata);
        cassandraColumns.add(xintColumnMetadata);
        cassandraColumns.add(xtimeColumnMetadata);
        cassandraColumns.add(xdateColumnMetadata);
        cassandraColumns.add(xblobColumnMetadata);

        Mockito.when(tableMetadata.primaryKeyColumns()).thenReturn(cassandraColumns);

        Mockito.when(exportedTable.getCassandraTable()).thenReturn(tableMetadata);
        List<com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata > columns = new ArrayList<>();
        columns.add(new DefaultColumnMetadata(CqlIdentifier.fromInternal("ks1"), CqlIdentifier.fromInternal("table1"), CqlIdentifier.fromInternal("xtext"), DataTypes.TEXT, false));
        columns.add(new DefaultColumnMetadata(CqlIdentifier.fromInternal("ks1"), CqlIdentifier.fromInternal("table1"), CqlIdentifier.fromInternal("xboolean"), DataTypes.BOOLEAN, false));
        columns.add(new DefaultColumnMetadata(CqlIdentifier.fromInternal("ks1"), CqlIdentifier.fromInternal("table1"), CqlIdentifier.fromInternal("xint"), DataTypes.INT, false));
        columns.add(new DefaultColumnMetadata(CqlIdentifier.fromInternal("ks1"), CqlIdentifier.fromInternal("table1"), CqlIdentifier.fromInternal("xtime"), DataTypes.TIME, false));
        columns.add(new DefaultColumnMetadata(CqlIdentifier.fromInternal("ks1"), CqlIdentifier.fromInternal("table1"), CqlIdentifier.fromInternal("xdate"), DataTypes.DATE, false));
        columns.add(new DefaultColumnMetadata(CqlIdentifier.fromInternal("ks1"), CqlIdentifier.fromInternal("table1"), CqlIdentifier.fromInternal("xblob"), DataTypes.BLOB, false));
        Mockito.when(exportedTable.getPrimaryKey()).thenReturn(columns);

        // when
        ExitStatus status = importer.importTable();

        // then
        assertEquals(ExitStatus.STATUS_OK, status);
        Mockito.verify(sender, Mockito.times(2)).sendMutationAsync(abstractMutationCaptor.capture());
        List<AbstractMutation<TableMetadata>> pkValues = abstractMutationCaptor.getAllValues();
        assertEquals(2, pkValues.size());
        List<Object>[] allPkValues = pkValues.stream().map(v-> v.getPkValues()).map(Arrays::asList).toArray(List[]::new);
        assertThat(allPkValues[0], containsInRelativeOrder("vtext", true, 2, LocalTime.of(1, 2, 3).toNanoOfDay(),
                ByteBuffer.wrap(new byte[]{0x00, 0x01})));
        assertEquals(LocalDate.of(2023, 3, 2), cqlSimpleDateToLocalDate((Integer) allPkValues[0].get(4)));
        assertThat(allPkValues[1], containsInRelativeOrder("v2text", false, 3, LocalTime.of(1, 2, 4).toNanoOfDay(),
                ByteBuffer.wrap(new byte[]{0x01})));
        assertEquals(LocalDate.of(2023, 3, 1), cqlSimpleDateToLocalDate((Integer) allPkValues[1].get(4)));
    }

    private static String url(String resource) {
        return StringUtils.quoteJson(rawURL("/" +resource));
    }

    private static URL rawURL(String resource) {
        return PulsarImporterTest.class.getResource(resource);
    }

    /**
     * Convert a CQL date to an Avro date. See rules in {@link PulsarMutationSender#cqlToAvro}
     */
    private LocalDate cqlSimpleDateToLocalDate(int value) {
        long timeInMillis = Duration.ofDays(value + Integer.MIN_VALUE).toMillis();
        Instant instant = Instant.ofEpochMilli(timeInMillis);
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).toLocalDate();
    }
}