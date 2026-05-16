package com.wealthview.core.importservice;

import java.io.IOException;
import java.io.InputStream;

import com.wealthview.core.importservice.dto.ImportParseResult;

/**
 * Contract for parsing a transaction import stream into a structured result.
 * Implementations may parse CSV, OFX/QFX, or any other supported format.
 */
@FunctionalInterface
public interface ImportParser {

    ImportParseResult parse(InputStream inputStream) throws IOException;
}
