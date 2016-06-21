/**
 * Copyright 2016 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.confluent.examples.streams;

import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KStreamBuilder;
import org.apache.kafka.streams.kstream.TimeWindows;

import java.util.Properties;

/**
 * Demonstrates how to count things over time, using time windows.  In this specific example we
 * read from a user click stream and detect any such users as anomalous that have appeared more
 * than twice in the click stream during one minute.
 *
 * Note: This example uses lambda expressions and thus works with Java 8+ only.
 *
 * HOW TO RUN THIS EXAMPLE
 *
 * 1) Start Zookeeper and Kafka. Please refer to <a href='http://docs.confluent.io/3.0.0/quickstart.html#quickstart'>CP3.0.0
 * QuickStart</a>.
 *
 * 2) Create the input and output topics used by this example.
 *
 * <pre>
 * {@code
 * $ bin/kafka-topics --create --topic UserClicks \
 *                    --zookeeper localhost:2181 --partitions 1 --replication-factor 1
 * $ bin/kafka-topics --create --topic AnomalousUsers \
 *                    --zookeeper localhost:2181 --partitions 1 --replication-factor 1
 * }
 * </pre>
 *
 * Note: The above commands are for CP 3.0.0 only. For Apache Kafka it should be
 * `bin/kafka-topics.sh ...`.
 *
 * 3) Start this example application either in your IDE or on the command line.
 *
 * If via the command line please refer to <a href='https://github.com/confluentinc/examples/tree/master/kafka-streams#packaging-and-running'>Packaging</a>.
 * Once packaged you can then run:
 *
 * <pre>
 * {@code
 * $ java -cp target/streams-examples-3.0.0-standalone.jar io.confluent.examples.streams.AnomalyDetectionLambdaExample
 * }
 * </pre>
 *
 * 4) Write some input data to the source topics (e.g. via `kafka-console-producer`.  The already
 * running example application (step 3) will automatically process this input data and write the
 * results to the output topics.
 *
 * <pre>
 * {@code
 * # Start the console producer.  You can then enter input data by writing some line of text,
 * # followed by ENTER.  The input data you enter should be some example usernames;  and because
 * # this example is set to detect only such users as "anomalous" that appear at least three times
 * # during a 1-minute time window, you should enter at least one username three times -- otherwise
 * # this example won't produce any output data (cf. step 5).
 * #
 * #   alice<ENTER>
 * #   alice<ENTER>
 * #   bob<ENTER>
 * #   alice<ENTER>
 * #   alice<ENTER>
 * #   charlie<ENTER>
 * #
 * # Every line you enter will become the value of a single Kafka message.
 * $ bin/kafka-console-producer --broker-list localhost:9092 --topic UserClicks
 * }
 * </pre>
 *
 * 5) Inspect the resulting data in the output topics, e.g. via `kafka-console-consumer`. Note that
 * it may take a while until you see some output in the console because, by default, the example
 * application will operate on 1-minute tumbling windows (so initially you must wait for one minute
 * to see the first output).
 *
 * <pre>
 * {@code
 * $ bin/kafka-console-consumer --topic AnomalousUsers --from-beginning \
 *        --zookeeper localhost:2181 \
 *        --property print.key=true \
 *        --property value.deserializer=org.apache.kafka.common.serialization.LongDeserializer
 * }
 * </pre>
 *
 * You should see output data similar to:
 *
 * <pre>
 * {@code
 * alice   3
 * alice   4
 * }
 * </pre>
 *
 * 6) Once you're done with your experiments, you can stop this example via `Ctrl-C`.  If needed,
 * also stop the Kafka broker (`Ctrl-C`), and only then stop the ZooKeeper instance (`Ctrl-C`).
 */
public class AnomalyDetectionLambdaExample {

  public static void main(String[] args) throws Exception {
    Properties streamsConfiguration = new Properties();
    // Give the Streams application a unique name.  The name must be unique in the Kafka cluster
    // against which the application is run.
    streamsConfiguration.put(StreamsConfig.APPLICATION_ID_CONFIG, "anomaly-detection-lambda-example");
    // Where to find Kafka broker(s).
    streamsConfiguration.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
    // Where to find the corresponding ZooKeeper ensemble.
    streamsConfiguration.put(StreamsConfig.ZOOKEEPER_CONNECT_CONFIG, "localhost:2181");
    // Specify default (de)serializers for record keys and for record values.
    streamsConfiguration.put(StreamsConfig.KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
    streamsConfiguration.put(StreamsConfig.VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());

    final Serde<String> stringSerde = Serdes.String();
    final Serde<Long> longSerde = Serdes.Long();

    KStreamBuilder builder = new KStreamBuilder();

    // Read the source stream.  In this example, we ignore whatever is stored in the record key and
    // assume the record value contains the username (and each record would represent a single
    // click by the corresponding user).
    KStream<String, String> views = builder.stream("UserClicks");

    KStream<String, Long> anomalyUsers = views
        // map the user name as key, because the subsequent counting is performed based on the key
        .map((ignoredKey, username) -> new KeyValue<>(username, username))
        // count users, using one-minute tumbling windows
        .countByKey(TimeWindows.of("UserCountWindow", 60 * 1000L))
        // get users whose one-minute count is >= 3
        .filter((windowedUserId, count) -> count >= 3)
        //
        // Note: The following operations would NOT be needed for the actual anomaly detection,
        // which would normally stop at the filter() above.  We use the operations below only to
        // "massage" the output data so it is easier to inspect on the console via
        // kafka-console-consumer.
        //
        // get rid of windows (and the underlying KTable) by transforming the KTable to a KStream
        .toStream()
        // sanitize the output by removing null record values (again, we do this only so that the
        // output is easier to read via kafka-console-consumer combined with LongDeserializer
        // because LongDeserializer fails on null values, and even though we could configure
        // kafka-console-consumer to skip messages on error the output still wouldn't look pretty)
        .filter((windowedUserId, count) -> count != null)
        .map((windowedUserId, count) -> new KeyValue<>(windowedUserId.key(), count));

    // write to the result topic
    anomalyUsers.to(stringSerde, longSerde, "AnomalousUsers");

    KafkaStreams streams = new KafkaStreams(builder, streamsConfiguration);
    streams.start();
  }

}