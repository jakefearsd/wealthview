package com.wealthview.projection.testutil;

import java.util.ArrayList;
import java.util.List;

public final class TierJsonBuilder {

    private final List<TierEntry> entries = new ArrayList<>();

    private TierJsonBuilder() {}

    public static TierJsonBuilder tiers() {
        return new TierJsonBuilder();
    }

    public TierJsonBuilder tier(String name, int startAge, Integer endAge,
                                 String essentialExpenses, String discretionaryExpenses) {
        entries.add(new TierEntry(name, startAge, endAge, essentialExpenses, discretionaryExpenses));
        return this;
    }

    public String build() {
        return buildWithFormat("startAge", "endAge", "essentialExpenses", "discretionaryExpenses");
    }

    public String buildSnakeCase() {
        return buildWithFormat("start_age", "end_age", "essential_expenses", "discretionary_expenses");
    }

    private String buildWithFormat(String startAgeKey, String endAgeKey,
                                    String essentialKey, String discretionaryKey) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(",");
            var e = entries.get(i);
            sb.append("{")
              .append("\"name\":\"").append(e.name).append("\",")
              .append("\"").append(startAgeKey).append("\":").append(e.startAge).append(",")
              .append("\"").append(endAgeKey).append("\":")
              .append(e.endAge != null ? e.endAge.toString() : "null").append(",")
              .append("\"").append(essentialKey).append("\":").append(e.essentialExpenses).append(",")
              .append("\"").append(discretionaryKey).append("\":").append(e.discretionaryExpenses)
              .append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private record TierEntry(String name, int startAge, Integer endAge,
                              String essentialExpenses, String discretionaryExpenses) {}
}
