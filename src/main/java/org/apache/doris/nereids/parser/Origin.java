// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
package org.apache.doris.nereids.parser;

import java.util.Optional;

/**
 * Record for token's line number and position in line.
 * Copied from Apache Doris FE; used by this sidecar with Origin wired.
 * Author: kejiqing
 */
public class Origin {
    public final Optional<Integer> line;
    public final Optional<Integer> startPosition;

    public Origin(int line, int startPosition) {
        this(Optional.of(line), Optional.of(startPosition));
    }

    public Origin(Optional<Integer> line, Optional<Integer> startPosition) {
        this.line = line;
        this.startPosition = startPosition;
    }
}
