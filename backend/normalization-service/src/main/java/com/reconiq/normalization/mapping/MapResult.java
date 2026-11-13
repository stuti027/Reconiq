package com.reconiq.normalization.mapping;

import com.reconiq.canonical.CanonicalTransaction;

public record MapResult(CanonicalTransaction transaction, boolean statusUnmapped) {
}