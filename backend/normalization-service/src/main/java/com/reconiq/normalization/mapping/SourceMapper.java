package com.reconiq.normalization.mapping;

import com.reconiq.normalization.input.ParsedRawEvent;

public interface SourceMapper {
    String source();
    MapResult map(ParsedRawEvent event);
}