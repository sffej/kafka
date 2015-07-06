package io.confluent.streaming.internal;

import io.confluent.streaming.util.FilteredIterator;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.Deserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class IngestorImpl<K, V> implements Ingestor {

  private static final Logger log = LoggerFactory.getLogger(IngestorImpl.class);

  private final Consumer<byte[], byte[]> consumer;
  private final Set<TopicPartition> unpaused = new HashSet<TopicPartition>();
  private final Set<TopicPartition> toBePaused = new HashSet<TopicPartition>();
  private final Deserializer<K> keyDeserializer;
  private final Deserializer<V> valueDeserializer;
  private final long pollTimeMs;
  private final Map<TopicPartition, StreamSynchronizer<K, V>> streamSynchronizers =
    new HashMap<TopicPartition, StreamSynchronizer<K, V>>();

  public IngestorImpl(Consumer<byte[], byte[]> consumer,
                      Deserializer<K> keyDeserializer,
                      Deserializer<V> valueDeserializer,
                      long pollTimeMs) {
    this.consumer = consumer;
    this.keyDeserializer = keyDeserializer;
    this.valueDeserializer = valueDeserializer;
    this.pollTimeMs = pollTimeMs;
  }

  public void init() {
    unpaused.clear();
    unpaused.addAll(consumer.subscriptions());
  }

  @Override
  public void poll() {
    poll(pollTimeMs);
  }

  @Override
  public void poll(long timeoutMs) {
    ConsumerRecords<byte[], byte[]> records;

    synchronized (this) {
      for (TopicPartition partition : toBePaused) {
        doPause(partition);
      }
      toBePaused.clear();

      records = consumer.poll(timeoutMs);
    }

    for (TopicPartition partition : unpaused) {
      StreamSynchronizer<K, V> streamSynchronizer = streamSynchronizers.get(partition);

      if (streamSynchronizer != null)
        streamSynchronizer.addRecords(partition, new DeserializingIterator(records.records(partition).iterator()));
      else
        log.warn("unused topic: " + partition.topic());
    }
  }

  @Override
  public void pause(TopicPartition partition) {
    toBePaused.add(partition);
  }

  private void doPause(TopicPartition partition) {
    consumer.seek(partition, Long.MAX_VALUE); // hack: stop consuming from this partition by setting a big offset
    unpaused.remove(partition);
  }

  @Override
  public void unpause(TopicPartition partition, long lastOffset) {
    synchronized (this) {
      consumer.seek(partition, lastOffset);
      unpaused.add(partition);
    }
  }

  @Override
  public int numPartitions(String topic) {
    return consumer.partitionsFor(topic).size();
  }

  @SuppressWarnings("unchecked")
  @Override
  public void addStreamSynchronizerForPartition(StreamSynchronizer<?, ?> streamSynchronizer, TopicPartition partition) {
    synchronized (this) {
      streamSynchronizers.put(partition, (StreamSynchronizer<K, V>) streamSynchronizer);
      unpaused.add(partition);
    }
  }

  @Override
  public void removeStreamSynchronizerForPartition(TopicPartition partition) {
    synchronized (this) {
      streamSynchronizers.remove(partition);
      unpaused.remove(partition);
      toBePaused.remove(partition);
    }
  }

  public void clear() {
    unpaused.clear();
    toBePaused.clear();
    streamSynchronizers.clear();
  }

  private class DeserializingIterator extends FilteredIterator<ConsumerRecord<K, V>, ConsumerRecord<byte[], byte[]>> {

    DeserializingIterator(Iterator<ConsumerRecord<byte[], byte[]>> inner) {
      super(inner);
    }

    protected ConsumerRecord<K, V> filter(ConsumerRecord<byte[], byte[]> record) {
      K key = keyDeserializer.deserialize(record.topic(), record.key());
      V value = valueDeserializer.deserialize(record.topic(), record.value());
      return new ConsumerRecord<K, V>(record.topic(), record.partition(), record.offset(), key, value);
    }

  }

}
