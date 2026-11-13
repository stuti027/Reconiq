package com.reconiq.normalization.input;

/** Outcome of parsing one input message: either a parsed event or a rejection. */
public sealed interface ParseResult permits ParsedRawEvent, Rejection {
}