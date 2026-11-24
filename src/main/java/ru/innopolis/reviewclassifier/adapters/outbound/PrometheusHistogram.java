package ru.innopolis.reviewclassifier.adapters.outbound;

import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/** Lock-free Prometheus histogram collector supporting cumulative bucket formatting. */
final class PrometheusHistogram {
    private final String name;
    private final String help;
    private final double[] upperBounds;
    private final LongAdder[] exactBuckets;
    private final LongAdder count = new LongAdder();
    private final DoubleAdder sum = new DoubleAdder();

    PrometheusHistogram(String name, String help, double... upperBounds) {
        this.name = name;
        this.help = help;
        this.upperBounds = upperBounds.clone();
        this.exactBuckets = new LongAdder[this.upperBounds.length + 1];
        for (int i = 0; i < this.exactBuckets.length; i++) {
            this.exactBuckets[i] = new LongAdder();
        }
    }

    void observe(double value) {
        double safeValue = Math.max(0.0, value);
        count.increment();
        sum.add(safeValue);
        for (int i = 0; i < upperBounds.length; i++) {
            if (safeValue <= upperBounds[i]) {
                exactBuckets[i].increment();
                return;
            }
        }
        exactBuckets[upperBounds.length].increment();
    }

    long getCount() {
        return count.sum();
    }

    void appendTo(StringBuilder sb) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(" histogram\n");
        long cumulative = 0L;
        for (int i = 0; i < upperBounds.length; i++) {
            cumulative += exactBuckets[i].sum();
            sb.append(name)
                    .append("_bucket{le=\"")
                    .append(formatBound(upperBounds[i]))
                    .append("\"} ")
                    .append(cumulative)
                    .append('\n');
        }
        cumulative += exactBuckets[upperBounds.length].sum();
        sb.append(name).append("_bucket{le=\"+Inf\"} ").append(cumulative).append('\n');
        sb.append(name).append("_sum ").append(sum.sum()).append('\n');
        sb.append(name).append("_count ").append(count.sum()).append('\n');
    }

    private static String formatBound(double bound) {
        if (bound == Math.rint(bound)) {
            return Long.toString((long) bound);
        }
        return Double.toString(bound);
    }
}
