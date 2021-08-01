package de.novatec.tc.pseudonymize;

import de.novatec.tc.account.v1.Account;
import de.novatec.tc.account.v1.ActionEvent;
import de.novatec.tc.support.AppConfigs;
import de.novatec.tc.support.Configs;
import de.novatec.tc.support.SerdeBuilder;
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.*;
import org.apache.kafka.streams.state.KeyValueStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;

import static io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PseudonymizeAppTest {

    @Test
    void shouldPseudonymizePersonalData() {
        runWithTopologyTestDriver(scope -> {
            // Produce some input data to the input topic.
            String inputKey = "accountA";
            ActionEvent inputEvent = ActionEvent.newBuilder()
                    .setEventId(randomUUID().toString())
                    .setEventTime(Instant.now())
                    .setAccountId(inputKey)
                    .setAccountName("Anja Abele")
                    .setAction("start")
                    .build();
            scope.input.pipeInput(inputKey, inputEvent);

            // Verify the application's output data.
            KeyValue<String, ActionEvent> receivedEvent = scope.output.readKeyValue();
            assertThat(receivedEvent.key).isNotBlank();
            assertThat(receivedEvent.key).isNotEqualTo(inputKey);
            assertThat(receivedEvent.value).isNotNull();
            assertThat(receivedEvent.value.getAccountId()).isNotBlank();
            assertThat(receivedEvent.value.getAccountId()).isNotEqualTo(inputEvent.getAccountId());
            assertThat(receivedEvent.value.getAccountName()).isNotBlank();
            assertThat(receivedEvent.value.getAccountName()).isNotEqualTo(inputEvent.getAccountName());
            assertThat(receivedEvent.value.getEventId()).isNotBlank();
            assertThat(receivedEvent.value.getEventId()).isNotEqualTo(inputEvent.getEventId());
            assertThat(receivedEvent.value.getEventTime()).isEqualTo(inputEvent.getEventTime());
            assertThat(receivedEvent.value.getAction()).isEqualTo(inputEvent.getAction());
            assertThat(receivedEvent.key).isEqualTo(receivedEvent.value.getAccountId());
            assertTrue(scope.output.isEmpty());
        });
    }

    @Test
    void shouldPseudonymizeWithSameValuesForSameKey() {
        runWithTopologyTestDriver(scope -> {
            // Produce some input data to the input topic.
            String inputKeyA = "accountA";
            ActionEvent inputEvent1 = ActionEvent.newBuilder()
                    .setEventId(randomUUID().toString())
                    .setEventTime(Instant.now())
                    .setAccountId(inputKeyA)
                    .setAccountName("Anja Abele")
                    .setAction("start")
                    .build();
            scope.input.pipeInput(inputKeyA, inputEvent1);
            ActionEvent inputEvent2 = ActionEvent.newBuilder()
                    .setEventId(randomUUID().toString())
                    .setEventTime(Instant.now())
                    .setAccountId(inputKeyA)
                    .setAccountName("Diana Deuss")
                    .setAction("accelerate")
                    .build();
            scope.input.pipeInput(inputKeyA, inputEvent2);

            // Verify the application's output data.
            KeyValue<String, ActionEvent> receivedEvent1 = scope.output.readKeyValue();
            KeyValue<String, ActionEvent> receivedEvent2 = scope.output.readKeyValue();
            assertThat(receivedEvent1.value.getAction()).isEqualTo(inputEvent1.getAction());
            assertThat(receivedEvent2.value.getAction()).isEqualTo(inputEvent2.getAction());
            assertThat(receivedEvent1.key).isEqualTo(receivedEvent2.key);
            // This is a deliberately simple implementation
            // that always uses the same values per account for all other person-related fields.
            assertThat(receivedEvent1.value.getAccountName()).isEqualTo(receivedEvent2.value.getAccountName());
            // The event id is unique for each event,
            // so a new id is simply always generated here to prevent a reference to the source event.
            assertThat(receivedEvent1.value.getEventId()).isNotEqualTo(receivedEvent2.value.getEventId());
            assertTrue(scope.output.isEmpty());
        });
    }

    static final String PROPERTIES_FILE = "pseudonymize.properties";

    static final String SCHEMA_REGISTRY_SCOPE = PseudonymizeAppTest.class.getName();
    static final String MOCK_SCHEMA_REGISTRY_URL = "mock://" + SCHEMA_REGISTRY_SCOPE;

    @AfterEach
    void tearDown() {
        MockSchemaRegistry.dropScope(SCHEMA_REGISTRY_SCOPE);
    }

    void runWithTopologyTestDriver(Consumer<TestScope> testAction) {
        final AppConfigs appConfigs = new AppConfigs(Configs.combined(
                Configs.fromResource(PROPERTIES_FILE),
                Map.of(SCHEMA_REGISTRY_URL_CONFIG, MOCK_SCHEMA_REGISTRY_URL)));

        final SerdeBuilder<String> stringSerdeBuilder = SerdeBuilder.fromSerdeSupplier(Serdes.StringSerde::new);
        final SerdeBuilder<ActionEvent> actionEventSerdeBuilder = SerdeBuilder.fromSerdeSupplier(SpecificAvroSerde::new);

        final Topology topology = new PseudonymizeApp().buildTopology(appConfigs.asMap());

        try (final TopologyTestDriver driver = new TopologyTestDriver(topology, appConfigs.asProperties())) {

            // Setup input and output topics.
            final TestInputTopic<String, ActionEvent> input = driver
                    .createInputTopic(appConfigs.topicName("input"),
                            stringSerdeBuilder.build(appConfigs.asMap(), true).serializer(),
                            actionEventSerdeBuilder.build(appConfigs.asMap(), false).serializer());
            final TestOutputTopic<String, ActionEvent> output = driver
                    .createOutputTopic(appConfigs.topicName("output"),
                            stringSerdeBuilder.build(appConfigs.asMap(), true).deserializer(),
                            actionEventSerdeBuilder.build(appConfigs.asMap(), false).deserializer());

            // Retrieve state stores
            final KeyValueStore<String, Account> pseudonymStore = driver.getKeyValueStore(appConfigs.storeName("pseudonym"));

            // Run actual test action
            testAction.accept(new TestScope(input, output, pseudonymStore));
        }
    }

    static record TestScope(TestInputTopic<String, ActionEvent> input,
                            TestOutputTopic<String, ActionEvent> output,
                            KeyValueStore<String, Account> pseudonymStore) {}
}