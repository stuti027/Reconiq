package com.reconiq.normalization.mapping;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class MapperRegistry {

    private final Map<String, SourceMapper> mappers = new HashMap<>();

    public MapperRegistry(List<SourceMapper> mappers) {
        for (SourceMapper mapper : mappers) {
            this.mappers.put(mapper.source().toUpperCase(Locale.ROOT), mapper);
        }
    }

    public Optional<SourceMapper> get(String source) {
        if (source == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mappers.get(source.trim().toUpperCase(Locale.ROOT)));
    }
}
