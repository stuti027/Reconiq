package com.reconiq.reporting.common;

import java.util.List;

/** Contract 1.5: {items, page, size, total}. */
public record PageResult<T>(List<T> items, int page, int size, long total) {
}
