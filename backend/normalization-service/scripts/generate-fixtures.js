// Generates test fixtures from ../../../sample-data-file/sample_data.json into
// ../src/test/resources/fixtures/. Run with: node generate-fixtures.js
//
// wire-events.jsonl  - one JSON object per line, timestamps as epoch-second numbers
//                       (the shape ingestion actually publishes)
// iso-events.jsonl   - one JSON object per line, timestamps as the sample file's own
//                       ISO-8601 strings (exercises the parser's other branch)
// bad-messages.jsonl - one {"reasonCode": "...", "raw": "<message text>"} object per line;
//                       each raw value is a deliberately invalid input message

const fs = require("fs");
const path = require("path");

const SAMPLE_PATH = path.join(__dirname, "..", "..", "..", "sample-data-file", "sample_data.json");
const OUT_DIR = path.join(__dirname, "..", "src", "test", "resources", "fixtures");

const sample = JSON.parse(fs.readFileSync(SAMPLE_PATH, "utf8"));
const events = sample.events;

fs.mkdirSync(OUT_DIR, { recursive: true });

function toEpochSeconds(isoString) {
    return Math.floor(Date.parse(isoString) / 1000);
}

function toWireShape(event) {
    const copy = JSON.parse(JSON.stringify(event));
    copy.event_timestamp = toEpochSeconds(event.event_timestamp);
    const txn = copy.data && copy.data.transaction;
    if (txn && txn.timestamp) {
        txn.timestamp = toEpochSeconds(txn.timestamp);
    }
    return copy;
}

function writeJsonl(filePath, objects) {
    const lines = objects.map((o) => JSON.stringify(o));
    fs.writeFileSync(filePath, lines.join("\n") + "\n", "utf8");
    console.log(`wrote ${objects.length} lines to ${filePath}`);
}

writeJsonl(path.join(OUT_DIR, "wire-events.jsonl"), events.map(toWireShape));
writeJsonl(path.join(OUT_DIR, "iso-events.jsonl"), events);

// A real, fully valid gateway event used as the template for the bad-message variants below.
const template = events.find((e) => e.source === "PAYMENT_GATEWAY" && e.data && e.data.transaction);

function mutate(fn) {
    const copy = JSON.parse(JSON.stringify(template));
    fn(copy);
    return JSON.stringify(copy);
}

const badMessages = [
    { reasonCode: "INVALID_JSON", raw: "not json" },
    { reasonCode: "INVALID_JSON", raw: "" },
    { reasonCode: "INVALID_JSON", raw: "[1,2]" },
    { reasonCode: "MISSING_EVENT_ID", raw: mutate((e) => { delete e.event_id; }) },
    { reasonCode: "INVALID_TIMESTAMP", raw: mutate((e) => { e.event_timestamp = "yesterday"; }) },
    { reasonCode: "INVALID_TIMESTAMP", raw: mutate((e) => { e.event_timestamp = 12345; }) },
    { reasonCode: "INVALID_TIMESTAMP", raw: mutate((e) => { e.event_timestamp = 999999999999999; }) },
    { reasonCode: "UNKNOWN_SOURCE", raw: mutate((e) => { e.source = "CARRIER_PIGEON"; }) },
    { reasonCode: "MISSING_TRANSACTION", raw: mutate((e) => { e.data = null; }) },
    { reasonCode: "MISSING_TRANSACTION_ID", raw: mutate((e) => { delete e.data.transaction.transaction_id; }) },
    { reasonCode: "INVALID_AMOUNT", raw: mutate((e) => { e.data.transaction.amount.value = "abc"; }) },
    { reasonCode: "INVALID_AMOUNT_SCALE", raw: mutate((e) => { e.data.transaction.amount.value = 10.005; }) },
];

writeJsonl(path.join(OUT_DIR, "bad-messages.jsonl"), badMessages);
