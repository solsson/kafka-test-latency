package se.yolean.kafka.test.failover;

import java.util.Properties;

import javax.inject.Inject;
import javax.inject.Named;

import com.github.structlog4j.ILogger;
import com.github.structlog4j.SLoggerFactory;

public class ProducerConsumerRun {

	private ILogger log = SLoggerFactory.getLogger(this.getClass());

	@Inject
	@Named("producerDefaults")
	private Properties producerProps;

	@Inject
	@Named("consumerDefaults")
	private Properties consumerProps;

	@Inject
	@Named("config:topic")
	private String topic;

	public void start() {
		log.info("Starting", "bootstrap", producerProps.getProperty("bootstrap.servers"), "topic", topic);

	}

}
