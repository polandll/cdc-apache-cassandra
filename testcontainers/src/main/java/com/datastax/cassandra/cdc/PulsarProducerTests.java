/**
 * Copyright DataStax, Inc 2021.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.cassandra.cdc;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.testcontainers.cassandra.CassandraContainer;
import com.datastax.testcontainers.pulsar.PulsarContainer;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.*;
import org.apache.pulsar.client.api.schema.Field;
import org.apache.pulsar.client.api.schema.GenericRecord;
import org.apache.pulsar.client.api.schema.RecordSchemaBuilder;
import org.apache.pulsar.client.api.schema.SchemaBuilder;
import org.apache.pulsar.common.schema.KeyValue;
import org.apache.pulsar.common.schema.KeyValueEncodingType;
import org.apache.pulsar.common.schema.SchemaInfo;
import org.apache.pulsar.common.schema.SchemaType;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Base class for producer unit tests.
 */
@Slf4j
public abstract class PulsarProducerTests {

    private static Network testNetwork;
    private static PulsarContainer<?> pulsarContainer;

    public static final DockerImageName PULSAR_IMAGE = DockerImageName.parse(
            Optional.ofNullable(System.getenv("PULSAR_IMAGE"))
                    .orElse("harbor.sjc.dsinternal.org/pulsar/lunastreaming-all:latest-272")
    ).asCompatibleSubstituteFor("pulsar");

    public abstract CassandraContainer<?> createCassandraContainer(int nodeIndex, String pulsarServiceUrl, Network testNetwork);

    public void drain(CassandraContainer... cassandraContainers) throws IOException, InterruptedException {
        // do nothing by default
    }

    public static void initBeforeClass() throws Exception {
        testNetwork = Network.newNetwork();
        pulsarContainer = new PulsarContainer<>(PULSAR_IMAGE)
                .withNetwork(testNetwork)
                .withCreateContainerCmdModifier(createContainerCmd -> createContainerCmd.withName("pulsar"))
                .withStartupTimeout(Duration.ofSeconds(30));
        pulsarContainer.start();
        Container.ExecResult result = pulsarContainer.execInContainer(
                "/pulsar/bin/pulsar-admin", "namespaces", "set-is-allow-auto-update-schema", "public/default", "--enable");
        assertEquals(0, result.getExitCode());
        result = pulsarContainer.execInContainer(
                "/pulsar/bin/pulsar-admin", "namespaces", "set-deduplication", "public/default", "--enable");
        assertEquals(0, result.getExitCode());
    }

    public static void closeAfterAll() {
        pulsarContainer.close();
    }

    @Test
    public void testProducer() throws InterruptedException, IOException {
        String pulsarServiceUrl = "pulsar://pulsar:" + pulsarContainer.BROKER_PORT;
        try (CassandraContainer<?> cassandraContainer1 = createCassandraContainer(1, pulsarServiceUrl, testNetwork);
             CassandraContainer<?> cassandraContainer2 = createCassandraContainer(2, pulsarServiceUrl, testNetwork)) {
            cassandraContainer1.start();
            cassandraContainer2.start();

            try (CqlSession cqlSession = cassandraContainer1.getCqlSession()) {
                cqlSession.execute("CREATE KEYSPACE IF NOT EXISTS ks1 WITH replication = {'class':'SimpleStrategy','replication_factor':'2'};");
                cqlSession.execute("CREATE TABLE IF NOT EXISTS ks1.table1 (id text PRIMARY KEY, a int) WITH cdc=true");
                cqlSession.execute("INSERT INTO ks1.table1 (id, a) VALUES('1',1)");
                cqlSession.execute("INSERT INTO ks1.table1 (id, a) VALUES('2',1)");
                cqlSession.execute("INSERT INTO ks1.table1 (id, a) VALUES('3',1)");

                cqlSession.execute("CREATE TABLE IF NOT EXISTS ks1.table2 (a text, b int, c int, PRIMARY KEY(a,b)) WITH cdc=true");
                cqlSession.execute("INSERT INTO ks1.table2 (a,b,c) VALUES('1',1,1)");
                cqlSession.execute("INSERT INTO ks1.table2 (a,b,c) VALUES('2',1,1)");
                cqlSession.execute("INSERT INTO ks1.table2 (a,b,c) VALUES('3',1,1)");
            }

            drain(cassandraContainer1, cassandraContainer2);

            Map<String, List<UUID>> nodesTable1 = new HashMap<>();
            Map<String, List<UUID>> nodesTable2 = new HashMap<>();
            Map<String, List<String>> digestsTable1 = new HashMap<>();
            Map<String, List<String>> digestsTable2 = new HashMap<>();

            try (PulsarClient pulsarClient = PulsarClient.builder().serviceUrl(pulsarContainer.getPulsarBrokerUrl()).build()) {
                RecordSchemaBuilder recordSchemaBuilder1 = SchemaBuilder.record("ks1.table1");
                recordSchemaBuilder1.field("id").type(SchemaType.STRING).required();
                SchemaInfo keySchemaInfo1 = recordSchemaBuilder1.build(SchemaType.AVRO);
                Schema<GenericRecord> keySchema1 = Schema.generic(keySchemaInfo1);
                Schema<KeyValue<GenericRecord, MutationValue>> schema1 = Schema.KeyValue(
                        keySchema1,
                        Schema.AVRO(MutationValue.class),
                        KeyValueEncodingType.SEPARATED);
                // pulsar-admin schemas get "persistent://public/default/events-ks1.table1"
                // pulsar-admin topics peek-messages persistent://public/default/events-ks1.table1-partition-0 --count 3 --subscription sub1
                try (Consumer<KeyValue<GenericRecord, MutationValue>> consumer = pulsarClient.newConsumer(schema1)
                        .topic("events-ks1.table1")
                        .subscriptionName("sub1")
                        .subscriptionType(SubscriptionType.Key_Shared)
                        .subscriptionMode(SubscriptionMode.Durable)
                        .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                        .subscribe()) {
                    Message<KeyValue<GenericRecord, MutationValue>> msg;
                    while ((msg = consumer.receive(30, TimeUnit.SECONDS)) != null &&
                            nodesTable1.values().stream().mapToInt(List::size).sum() < 6) {
                        KeyValue<GenericRecord, MutationValue> kv = msg.getValue();
                        GenericRecord key = kv.getKey();
                        MutationValue val = kv.getValue();
                        System.out.println("Consumer Record: topicName=" + msg.getTopicName() +
                                " key=" + genericRecordToString(key) +
                                " value=" + val);
                        List<UUID> nodes = nodesTable1.computeIfAbsent((String) key.getField("id"), k -> new ArrayList<>());
                        nodes.add(val.getNodeId());
                        List<String> digests = digestsTable1.computeIfAbsent((String) key.getField("id"), k -> new ArrayList<>());
                        digests.add(val.getMd5Digest());
                        consumer.acknowledgeAsync(msg);
                    }
                }
                // check we have exactly one mutation per node for each key.
                for (int i = 1; i < 4; i++) {
                    Assert.assertNotNull(nodesTable1.get(Integer.toString(i)));
                    assertEquals(2, nodesTable1.get(Integer.toString(i)).size());
                    assertEquals(2, nodesTable1.get(Integer.toString(i)).stream().collect(Collectors.toSet()).size());
                }
                // check we have exactly 2 identical digests.
                for (int i = 1; i < 4; i++) {
                    Assert.assertNotNull(digestsTable1.get(Integer.toString(i)));
                    assertEquals(2, digestsTable1.get(Integer.toString(i)).size());
                    assertEquals(1, digestsTable1.get(Integer.toString(i)).stream().collect(Collectors.toSet()).size());
                }

                // pulsar-admin schemas get "persistent://public/default/events-ks1.table2"
                // pulsar-admin topics peek-messages persistent://public/default/events-ks1.table2-partition-0 --count 3 --subscription sub1
                RecordSchemaBuilder recordSchemaBuilder2 = SchemaBuilder.record("ks1.table2");
                recordSchemaBuilder2.field("a").type(SchemaType.STRING).required();
                recordSchemaBuilder2.field("b").type(SchemaType.INT32).optional().defaultValue(null);
                SchemaInfo keySchemaInfo2 = recordSchemaBuilder2.build(SchemaType.AVRO);
                Schema<GenericRecord> keySchema2 = Schema.generic(keySchemaInfo2);
                Schema<KeyValue<GenericRecord, MutationValue>> schema2 = Schema.KeyValue(
                        keySchema2,
                        Schema.AVRO(MutationValue.class),
                        KeyValueEncodingType.SEPARATED);
                try (Consumer<KeyValue<GenericRecord, MutationValue>> consumer = pulsarClient.newConsumer(schema2)
                        .topic("events-ks1.table2")
                        .subscriptionName("sub1")
                        .subscriptionType(SubscriptionType.Key_Shared)
                        .subscriptionMode(SubscriptionMode.Durable)
                        .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                        .subscribe()) {
                    Message<KeyValue<GenericRecord, MutationValue>> msg;
                    while ((msg = consumer.receive(30, TimeUnit.SECONDS)) != null &&
                            nodesTable2.values().stream().mapToInt(List::size).sum() < 6) {
                        KeyValue<GenericRecord, MutationValue> kv = msg.getValue();
                        GenericRecord key = kv.getKey();
                        MutationValue val = kv.getValue();
                        System.out.println("Consumer Record: topicName=" + msg.getTopicName() +
                                " key=" + genericRecordToString(key) +
                                " value=" + val);
                        assertEquals(1, key.getField("b"));
                        List<UUID> nodes = nodesTable2.computeIfAbsent((String) key.getField("a"), k -> new ArrayList<>());
                        nodes.add(val.getNodeId());
                        List<String> digests = digestsTable2.computeIfAbsent((String) key.getField("a"), k -> new ArrayList<>());
                        digests.add(val.getMd5Digest());
                        consumer.acknowledgeAsync(msg);
                    }
                }
                // check we have exactly one mutation per node for each key.
                for (int i = 1; i < 4; i++) {
                    assertEquals(2, nodesTable2.get(Integer.toString(i)).size());
                    assertEquals(2, nodesTable2.get(Integer.toString(i)).stream().collect(Collectors.toSet()).size());
                }
                // check we have exactly 2 identical digests.
                for (int i = 1; i < 4; i++) {
                    assertEquals(2, digestsTable2.get(Integer.toString(i)).size());
                    assertEquals(1, digestsTable2.get(Integer.toString(i)).stream().collect(Collectors.toSet()).size());
                }
            }
        }
    }

    static String genericRecordToString(GenericRecord genericRecord) {
        StringBuilder sb = new StringBuilder("{");
        for (Field field : genericRecord.getFields()) {
            if (sb.length() > 1)
                sb.append(",");
            sb.append(field.getName()).append("=");
            if (genericRecord.getField(field) instanceof GenericRecord) {
                sb.append(genericRecordToString((GenericRecord) genericRecord.getField(field)));
            } else {
                sb.append(genericRecord.getField(field) == null ? "null" : genericRecord.getField(field).toString());
            }
        }
        return sb.append("}").toString();
    }

    static Map<String, Object> genericRecordToMap(GenericRecord genericRecord) {
        Map<String, Object> map = new HashMap<>();
        for (Field field : genericRecord.getFields()) {
            if (genericRecord.getField(field) instanceof GenericRecord) {
                map.put(field.getName(), genericRecordToString((GenericRecord) genericRecord.getField(field)));
            } else {
                map.put(field.getName(), genericRecord.getField(field));
            }
        }
        return map;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testSchema() throws IOException, InterruptedException {
        final String pulsarServiceUrl = "pulsar://pulsar:" + pulsarContainer.BROKER_PORT;

        final ZoneId zone = ZoneId.systemDefault();
        final LocalDate localDate = LocalDate.of(2020, 12, 25);
        final LocalDateTime localDateTime = localDate.atTime(10, 10, 00);

        // sample values, left=CQL value, right=Pulsar value
        Map<String, Object[]> values = new HashMap<>();
        values.put("xtext", new Object[]{"a", "a"});
        values.put("xascii", new Object[]{"aa", "aa"});
        values.put("xboolean", new Object[]{true, true});
        values.put("xblob", new Object[]{ByteBuffer.wrap(new byte[]{0x00, 0x01}), ByteBuffer.wrap(new byte[]{0x00, 0x01})});
        values.put("xtimestamp", new Object[]{localDateTime.atZone(zone).toInstant(), localDateTime.atZone(zone).toInstant().toEpochMilli()});
        values.put("xtime", new Object[]{localDateTime.toLocalTime(), (localDateTime.toLocalTime().toNanoOfDay() / 1000)});
        values.put("xdate", new Object[]{localDateTime.toLocalDate(), (int) localDateTime.toLocalDate().toEpochDay()});
        values.put("xuuid", new Object[]{UUID.fromString("01234567-0123-0123-0123-0123456789ab"), "01234567-0123-0123-0123-0123456789ab"});
        values.put("xtimeuuid", new Object[]{UUID.fromString("d2177dd0-eaa2-11de-a572-001b779c76e3"), "d2177dd0-eaa2-11de-a572-001b779c76e3"});
        values.put("xtinyint", new Object[]{(byte) 0x01, (int) 0x01}); // Avro only support integer
        values.put("xsmallint", new Object[]{(short) 1, (int) 1});     // Avro only support integer
        values.put("xint", new Object[]{1, 1});
        values.put("xbigint", new Object[]{1L, 1L});
        values.put("xdouble", new Object[]{1.0D, 1.0D});
        values.put("xfloat", new Object[]{1.0f, 1.0f});
        values.put("xinet4", new Object[]{Inet4Address.getLoopbackAddress(), Inet4Address.getLoopbackAddress().getHostAddress()});
        values.put("xinet6", new Object[]{Inet6Address.getLoopbackAddress(), Inet4Address.getLoopbackAddress().getHostAddress()});

        try (CassandraContainer<?> cassandraContainer1 = createCassandraContainer(1, pulsarServiceUrl, testNetwork)) {
            cassandraContainer1.start();
            try (CqlSession cqlSession = cassandraContainer1.getCqlSession()) {
                cqlSession.execute("CREATE KEYSPACE IF NOT EXISTS ks2 WITH replication = {'class':'SimpleStrategy','replication_factor':'1'};");
                cqlSession.execute("CREATE TABLE IF NOT EXISTS ks2.table1 (" +
                        "xtext text, xascii ascii, xboolean boolean, xblob blob, xtimestamp timestamp, xtime time, xdate date, xuuid uuid, xtimeuuid timeuuid, xtinyint tinyint, xsmallint smallint, xint int, xbigint bigint, xdouble double, xfloat float, xinet4 inet, xinet6 inet, " +
                        "primary key (xtext, xascii, xboolean, xblob, xtimestamp, xtime, xdate, xuuid, xtimeuuid, xtinyint, xsmallint, xint, xbigint, xdouble, xfloat, xinet4, xinet6)) WITH cdc=true");
                cqlSession.execute("INSERT INTO ks2.table1 (xtext, xascii, xboolean, xblob, xtimestamp, xtime, xdate, xuuid, xtimeuuid, xtinyint, xsmallint, xint, xbigint, xdouble, xfloat, xinet4, xinet6) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        values.get("xtext")[0],
                        values.get("xascii")[0],
                        values.get("xboolean")[0],
                        values.get("xblob")[0],
                        values.get("xtimestamp")[0],
                        values.get("xtime")[0],
                        values.get("xdate")[0],
                        values.get("xuuid")[0],
                        values.get("xtimeuuid")[0],
                        values.get("xtinyint")[0],
                        values.get("xsmallint")[0],
                        values.get("xint")[0],
                        values.get("xbigint")[0],
                        values.get("xdouble")[0],
                        values.get("xfloat")[0],
                        values.get("xinet4")[0],
                        values.get("xinet6")[0]
                );
            }

            drain(cassandraContainer1);

            try (PulsarClient pulsarClient = PulsarClient.builder().serviceUrl(pulsarContainer.getPulsarBrokerUrl()).build();
                 Consumer<GenericRecord> consumer = pulsarClient.newConsumer(Schema.AUTO_CONSUME())
                         .topic("events-ks2.table1")
                         .subscriptionName("sub1")
                         .subscriptionType(SubscriptionType.Key_Shared)
                         .subscriptionMode(SubscriptionMode.Durable)
                         .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                         .subscribe()) {
                Message<GenericRecord> msg = consumer.receive(60, TimeUnit.SECONDS);
                Assert.assertNotNull("Expecting one message, check the producer log", msg);
                GenericRecord gr = msg.getValue();
                KeyValue<GenericRecord, GenericRecord> kv = (KeyValue<GenericRecord, GenericRecord>) gr.getNativeObject();
                GenericRecord key = kv.getKey();
                System.out.println("Consumer Record: topicName=" + msg.getTopicName() + " key=" + genericRecordToString(key));
                Map<String, Object> map = genericRecordToMap(key);
                for (Field field : key.getFields()) {
                    Assert.assertEquals("Wrong value fo field " + field.getName(), values.get(field.getName())[1], map.get(field.getName()));
                }
                consumer.acknowledgeAsync(msg);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testStaticColumn() throws IOException, InterruptedException {
        String pulsarServiceUrl = "pulsar://pulsar:" + pulsarContainer.BROKER_PORT;
        try (CassandraContainer<?> cassandraContainer1 = createCassandraContainer(1, pulsarServiceUrl, testNetwork)) {
            cassandraContainer1.start();
            try (CqlSession cqlSession = cassandraContainer1.getCqlSession()) {
                cqlSession.execute("CREATE KEYSPACE IF NOT EXISTS ks3 WITH replication = {'class':'SimpleStrategy','replication_factor':'1'};");
                cqlSession.execute("CREATE TABLE IF NOT EXISTS ks3.table1 (a text, b text, c text, d text static, PRIMARY KEY ((a), b)) with cdc=true;");
                cqlSession.execute("INSERT INTO ks3.table1 (a,b,c,d) VALUES ('a','b','c','d1');");
                cqlSession.execute("INSERT INTO ks3.table1 (a,d) VALUES ('a','d2');");
                cqlSession.execute("DELETE FROM ks3.table1 WHERE a = 'a'");
            }

            drain(cassandraContainer1);

            try (PulsarClient pulsarClient = PulsarClient.builder().serviceUrl(pulsarContainer.getPulsarBrokerUrl()).build();
                 Consumer<GenericRecord> consumer = pulsarClient.newConsumer(Schema.AUTO_CONSUME())
                         .topic("events-ks3.table1")
                         .subscriptionName("sub1")
                         .subscriptionType(SubscriptionType.Key_Shared)
                         .subscriptionMode(SubscriptionMode.Durable)
                         .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                         .subscribe()) {
                Message<GenericRecord> msg = consumer.receive(120, TimeUnit.SECONDS);
                Assert.assertNotNull("Expecting one message, check the producer log", msg);
                GenericRecord gr = msg.getValue();
                KeyValue<GenericRecord, GenericRecord> kv = (KeyValue<GenericRecord, GenericRecord>) gr.getNativeObject();
                GenericRecord key = kv.getKey();
                Assert.assertEquals("a", key.getField("a"));
                Assert.assertEquals("b", key.getField("b"));
                consumer.acknowledgeAsync(msg);

                msg = consumer.receive(90, TimeUnit.SECONDS);
                Assert.assertNotNull("Expecting one message, check the producer log", msg);
                GenericRecord gr2 = msg.getValue();
                KeyValue<GenericRecord, GenericRecord> kv2 = (KeyValue<GenericRecord, GenericRecord>) gr2.getNativeObject();
                GenericRecord key2 = kv2.getKey();
                Assert.assertEquals("a", key2.getField("a"));
                Assert.assertEquals(null, key2.getField("b"));
                consumer.acknowledgeAsync(msg);

                msg = consumer.receive(90, TimeUnit.SECONDS);
                Assert.assertNotNull("Expecting one message, check the producer log", msg);
                GenericRecord gr3 = msg.getValue();
                KeyValue<GenericRecord, GenericRecord> kv3 = (KeyValue<GenericRecord, GenericRecord>) gr3.getNativeObject();
                GenericRecord key3 = kv3.getKey();
                Assert.assertEquals("a", key3.getField("a"));
                Assert.assertEquals(null, key3.getField("b"));
                consumer.acknowledgeAsync(msg);
            }
        }
    }
}