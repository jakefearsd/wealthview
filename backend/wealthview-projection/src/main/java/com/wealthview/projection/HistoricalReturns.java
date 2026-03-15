package com.wealthview.projection;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public final class HistoricalReturns {

    private static final double[] RETURNS;
    private static final int[] YEARS;

    static {
        var yearsList = new ArrayList<Integer>();
        var returnsList = new ArrayList<Double>();

        try (var is = HistoricalReturns.class.getResourceAsStream(
                "/historical-returns/sp500-real-annual-returns.csv");
             var reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

            String line = reader.readLine(); // skip header
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                var parts = line.split(",");
                yearsList.add(Integer.parseInt(parts[0]));
                returnsList.add(Double.parseDouble(parts[1]));
            }
        } catch (IOException e) {
            throw new ExceptionInInitializerError("Failed to load historical returns CSV: " + e.getMessage());
        }

        if (yearsList.size() < 100) {
            throw new ExceptionInInitializerError(
                    "Historical returns CSV has only " + yearsList.size() + " entries, expected >= 100");
        }

        YEARS = yearsList.stream().mapToInt(Integer::intValue).toArray();
        RETURNS = returnsList.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private HistoricalReturns() {}

    public static double[] getReturns() {
        return RETURNS.clone();
    }

    public static int[] getYears() {
        return YEARS.clone();
    }

    public static int size() {
        return RETURNS.length;
    }
}
