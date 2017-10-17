package se.yolean.kafka.test.failover.config;

import java.util.Properties;

import com.google.inject.AbstractModule;
import com.google.inject.name.Names;

public class ConfigModule extends AbstractModule {

	public static final String ENV_BOOTSTRAP = "BOOTSTRAP";
	// we could get these from kubectl get nodes
	public static final String DEFAULT_BOOTSTRAP_SERVERS = "192.168.99.100:32400,192.168.99.100:32401,192.168.99.100:32402";

	public static final String ENV_TOPICS = "TOPIC";
	public static final String DEFAULT_TOPICS = "test-basic-produce-consume";

	public String getConf(String envName, String fallback) {
		String conf = System.getenv(envName);
		if (conf != null)
			return conf;
		return fallback;
	}

	@Override
	protected void configure() {
		String bs = getConf(ENV_BOOTSTRAP, DEFAULT_BOOTSTRAP_SERVERS);
		bind(String.class).annotatedWith(Names.named("config:bootstrap")).toInstance(bs);

		// we called this TOPIC in Yolean/kubernetes-kafka tests
		String topics = getConf(ENV_TOPICS, DEFAULT_TOPICS);
		bind(String.class).annotatedWith(Names.named("config:topic")).toInstance(topics);

		bind(Properties.class).annotatedWith(Names.named("producerDefaults"))
				.toProvider(ProducerDefaultPropsProvider.class);
		bind(Properties.class).annotatedWith(Names.named("consumerDefaults"))
				.toProvider(ConsumerDefaultPropsProvider.class);

	}

}