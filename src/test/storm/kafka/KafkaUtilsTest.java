package storm.kafka;

import backtype.storm.utils.Utils;
import kafka.api.OffsetRequest;
import kafka.javaapi.consumer.SimpleConsumer;
import kafka.javaapi.message.ByteBufferMessageSet;
import kafka.javaapi.producer.Producer;
import kafka.producer.KeyedMessage;
import kafka.producer.ProducerConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import storm.kafka.trident.GlobalPartitionInformation;

import java.util.Properties;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertNull;

public class KafkaUtilsTest {

    private KafkaTestBroker broker;
    private SimpleConsumer simpleConsumer;
    private KafkaConfig config;

    @Before
    public void setup() {
        broker = new KafkaTestBroker();
        GlobalPartitionInformation globalPartitionInformation = new GlobalPartitionInformation();
        globalPartitionInformation.addPartition(0, Broker.fromString(broker.getBrokerConnectionString()));
        BrokerHosts brokerHosts = new StaticHosts(globalPartitionInformation);
        config = new KafkaConfig(brokerHosts, "testTopic");
        simpleConsumer = new SimpleConsumer("localhost", broker.getPort(), 60000, 1024, "testClient");
    }

    @After
    public void shutdown() {
        broker.shutdown();
    }


    @Test(expected = FailedFetchException.class)
    public void topicDoesNotExist() throws Exception {
        KafkaUtils.fetchMessages(config, simpleConsumer, new Partition(Broker.fromString(broker.getBrokerConnectionString()), 0), 0);
    }

    public void brokerIsDown() throws Exception {
        int port = broker.getPort();
        broker.shutdown();
        SimpleConsumer simpleConsumer = new SimpleConsumer("localhost", port, 100, 1024, "testClient");
        KafkaUtils.Response response = KafkaUtils.fetchMessages(config, simpleConsumer, new Partition(Broker.fromString(broker.getBrokerConnectionString()), 0), OffsetRequest.LatestTime());
        ByteBufferMessageSet messageAndOffsets = response.msgs;
        assertNull(messageAndOffsets);
    }

    @Test
    public void fetchMessage() throws Exception {
        long lastOffset = KafkaUtils.getOffset(simpleConsumer, config.topic, 0, OffsetRequest.EarliestTime());
        sendMessageAndAssertValueForOffset(lastOffset);
    }

    @Test(expected = FailedFetchException.class)
    public void fetchMessagesWithInvalidOffsetAndDefaultHandlingDisabled() throws Exception {
        config.useStartOffsetTimeIfOffsetOutOfRange = false;
        sendMessageAndAssertValueForOffset(-99);
    }

    @Test
    public void fetchMessagesWithInvalidOffsetAndDefaultHandlingEnabled() throws Exception {
        sendMessageAndAssertValueForOffset(-99);
    }

    @Test
    public void getOffsetFromConfigAndDontForceFromStart() {
        config.forceFromStart = false;
        config.startOffsetTime = OffsetRequest.EarliestTime();
        createTopicAndSendMessage();
        long latestOffset = KafkaUtils.getOffset(simpleConsumer, config.topic, 0, OffsetRequest.LatestTime());
        long offsetFromConfig = KafkaUtils.getOffset(simpleConsumer, config.topic, 0, config);
        assertThat(latestOffset, is(equalTo(offsetFromConfig)));
    }

    @Test
    public void getOffsetFromConfigAndFroceFromStart() {
        config.forceFromStart = true;
        config.startOffsetTime = OffsetRequest.EarliestTime();
        createTopicAndSendMessage();
        long earliestOffset = KafkaUtils.getOffset(simpleConsumer, config.topic, 0, OffsetRequest.EarliestTime());
        long offsetFromConfig = KafkaUtils.getOffset(simpleConsumer, config.topic, 0, config);
        assertThat(earliestOffset, is(equalTo(offsetFromConfig)));
    }

    private String createTopicAndSendMessage() {
        Properties p = new Properties();
        p.setProperty("metadata.broker.list", "localhost:49123");
        p.setProperty("serializer.class", "kafka.serializer.StringEncoder");
        ProducerConfig producerConfig = new ProducerConfig(p);
        Producer<String, String> producer = new Producer<String, String>(producerConfig);
        String value = "value";
        producer.send(new KeyedMessage<String, String>(config.topic, value));
        return value;
    }

    private void sendMessageAndAssertValueForOffset(long offset) {
        String value = createTopicAndSendMessage();
        KafkaUtils.Response response = KafkaUtils.fetchMessages(config, simpleConsumer, new Partition(Broker.fromString(broker.getBrokerConnectionString()), 0), offset);
        ByteBufferMessageSet messageAndOffsets = response.msgs;
        String message = new String(Utils.toByteArray(messageAndOffsets.iterator().next().message().payload()));
        assertThat(message, is(equalTo(value)));
    }
}
