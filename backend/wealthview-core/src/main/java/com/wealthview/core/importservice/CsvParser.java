package com.wealthview.core.importservice;

import com.wealthview.core.importservice.dto.CsvParseResult;

import java.io.IOException;
import java.io.InputStream;

@FunctionalInterface
public interface CsvParser {

    CsvParseResult parse(InputStream inputStream) throws IOException;
}
