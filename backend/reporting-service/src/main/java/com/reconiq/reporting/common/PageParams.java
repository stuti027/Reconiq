package com.reconiq.reporting.common;

/** Contract 1.5: page starts at 0; size defaults to 25 and may not exceed 100. */
public record PageParams(int page, int size) {

    public static final int DEFAULT_SIZE = 25;
    public static final int MAX_SIZE = 100;

    public static PageParams of(Integer page, Integer size) {
        int p = page == null ? 0 : page;
        int s = size == null ? DEFAULT_SIZE : size;
        if (p < 0) {
            throw new BadRequestException("page must be 0 or more");
        }
        if (s < 1 || s > MAX_SIZE) {
            throw new BadRequestException("size must be between 1 and " + MAX_SIZE);
        }
        return new PageParams(p, s);
    }

    public long offset() {
        return (long) page * size;
    }
}
