package se.yolean.kafka.test.failover;

import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

import com.github.structlog4j.ILogger;
import com.github.structlog4j.SLoggerFactory;

import io.prometheus.client.Counter;
import io.prometheus.client.Histogram;
import se.yolean.kafka.test.failover.analytics.TestMessageLog;
import se.yolean.kafka.test.failover.analytics.TestMessageLogImpl;

public class ProducerConsumerRun implements Runnable {

	private ILogger log = SLoggerFactory.getLogger(this.getClass());

	@Inject
	@Named("config:messagesMax")
	private int messagesMax;

	@Inject
	@Named("config:messageIntervalMs")
	private int messageIntervalMs;

	@Inject
	@Named("config:ackTimeoutMs")
	private int ackTimeoutMs;
	
	@Inject
	@Named("config:consumerPollMs")	
	private int consumerPollMs;

	@Inject
	@Named("producer")
	private Properties producerProps;

	@Inject
	@Named("consumer")
	private Properties consumerProps;

	@Inject
	@Named("config:topic")
	private String topic;

	static final Histogram iterationLatency = Histogram.build().name("iteration_latency")
			.help("Time taken for each test loop, excluding initial wait (ms)").register();

	static final Counter iterations = Counter.build().name("iterations").help("Test loop iterations started so far")
			.register();

	static final Counter iterationsDelayed = Counter.build().name("iterations_delayed")
			.help("Test loop iterations that failed to complete within the configured interval").register();

	static final Counter acksMissedTimeout = Counter.build().name("acks_missed_timeout")
			.help("Produce calls that failed to get an ack within the configured timeout").register();

	/**
	 * @param runId
	 *            Unique, in case runs share a topic
	 * @throws ConsistencyFatalError
	 *             On consistencies that are too odd/big to be represented by
	 *             metrics
	 */
	void start(RunId runId) throws ConsistencyFatalError {
		log.info("Starting", "runId", runId, "topic", topic, "bootstrap",
				producerProps.getProperty("bootstrap.servers"));

		TestMessageLog messageLog = new TestMessageLogImpl(runId);

		KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps);
		Producer<String, String> producer = new KafkaProducer<>(producerProps);

		try {
			start(messageLog, consumer, producer);
		} finally {
			producer.close();
			consumer.close();
		}
	}

	void start(TestMessageLog messageLog, KafkaConsumer<String, String> consumer, Producer<String, String> producer) {

		AlwaysSeekToEndListener<String, String> rebalanceListner = new AlwaysSeekToEndListener<>(consumer);
		consumer.subscribe(Arrays.asList(topic), rebalanceListner);

		long t = System.currentTimeMillis();
		for (int i = 0; i < messagesMax; i++) {
			iterations.inc();
			long durationPrevious = System.currentTimeMillis() - t;
			long wait = messageIntervalMs - durationPrevious;
			if (wait > 0) {
				try {
					Thread.sleep(wait);
				} catch (InterruptedException e) {
					throw new RuntimeException("Got aborted at wait", e);
				}
			} else {
				iterationsDelayed.inc();
				log.warn("Interval insufficient", "index", i, "duration", durationPrevious, "target",
						messageIntervalMs);
			}

			t = System.currentTimeMillis();
			Histogram.Timer iterationTimer = iterationLatency.startTimer();
			try {
				ProducerRecord<String, String> record = messageLog.createNext(i, topic);
				log.debug("Producer send", "key", record.key(), "afterWait", wait);
				Future<RecordMetadata> producing = producer.send(record);
				RecordMetadata metadata = waitForAck(producing);
				log.debug("Got producer ack", "topic", metadata.topic(), "partition", metadata.partition(), "offset",
						metadata.offset(), "timestamp", metadata.timestamp(), "keySize", metadata.serializedKeySize(),
						"valueSize", metadata.serializedValueSize());
				messageLog.onProducerAckReceived(i, metadata);

				ConsumerRecords<String, String> consumed = consumer.poll(consumerPollMs);
				for (ConsumerRecord<String, String> r : consumed) {
					log.info("consumed", "offset", r.offset(), "timestamp", r.timestamp(), "key", r.key(), "value",
							r.value());
					messageLog.onConsumed(r);
				}
			} finally {
				iterationTimer.observeDuration();
			}
		}
	}

	private RecordMetadata waitForAck(Future<RecordMetadata> producing) {
		// block while sending
		final RecordMetadata metadata;
		try {
			metadata = producing.get(ackTimeoutMs, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			log.error("Got interrupted (probably not by Kafka) while waiting for ack", e);
			throw new ConsistencyFatalError(e);
		} catch (ExecutionException e) {
			log.error("Something must have gone wrong while producing", e);
			throw new ConsistencyFatalError(e);
		} catch (TimeoutException e) {
			log.error("Failed to get an ack within", "milliseconds", ackTimeoutMs, e);
			acksMissedTimeout.inc();
			// TODO we most likely don't want to exit here, can we log this and proceed?
			throw new ConsistencyFatalError(e);
		}
		if (metadata == null) {
			throw new ConsistencyFatalError("Failed with reason unkown to get ack for message " + producing);
		}
		return metadata;
	}

	// --- to be able to run as thread ---

	private RunId runId = null;

	public void setRunId(RunId runId) {
		if (this.runId != null) throw new IllegalStateException("runs can't be reused");
		if (runId == null) throw new IllegalArgumentException("runId is required");
		this.runId = runId;
	}

	@Override
	public void run() {
		if (runId == null) throw new IllegalStateException("setRunId hasn't been called");
		start(runId);
	}

}
