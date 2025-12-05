package one.demo.challenge.level8;

import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 线程安全地维护消费者统计信息。
 */
@Service
public class Level8ConsumerStatsService {

    private final Map<String, Level8ConsumerStats> statsMap = new ConcurrentHashMap<>();

    public void record(String consumerName, Level8OrderMessage message, String note) {
        Level8ConsumerStats stats = statsMap.computeIfAbsent(consumerName, Level8ConsumerStats::new);
        stats.record(message, note);
    }

    public Level8ConsumerStats find(String consumerName) {
        return statsMap.get(consumerName);
    }

    public Collection<Level8ConsumerStats> all() {
        return statsMap.values();
    }

    public void reset() {
        statsMap.clear();
    }
}
