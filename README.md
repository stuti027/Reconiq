# Steps to Run Current Progress

## Terminal 1

### Start Infrastructure

docker compose -f docker-compose.yml -f backend/normalization-service/docker-compose.schema-registry.yml up -d

### Start Phase 3

cd backend
mvn spring-boot:run

---

## Terminal 2 — Bash Terminal

### Navigate to Normalization Service

cd backend/normalization-service

### Register Schema

scripts/register-schema.sh

### Build Normalization Service

mvn package -DskipTests

### Set Environment Variables

export APP_MODE=all
export KAFKA_HOST=localhost
export KAFKA_PORT=9092
export SCHEMA_REGISTRY_URL=http://localhost:8081
export POSTGRES_HOST=localhost
export POSTGRES_PORT=5432
export POSTGRES_DB=reconiq_db
export POSTGRES_USER=reconiq_user
export POSTGRES_PASSWORD=reconiq_password
export TOPICS_AUTO_CREATE=true
export HEALTH_PORT=8090

### Start Phase 2

java -Duser.timezone=UTC -jar target/normalization-service-0.1.0-SNAPSHOT.jar

---

## Terminal 3

### Start Kafka Console Producer

docker exec -it reconiq-kafka kafka-console-producer --bootstrap-server localhost:9092 --topic raw-transactions

### Paste Two Transactions

#### Gateway Transaction

{"event_id":"evt-final-gateway-001","event_timestamp":"2026-09-30T10:30:00Z","source":"PAYMENT_GATEWAY","data":{"transaction":{"transaction_id":"txn-final-001","reference_id":"ref-final-001","merchant_id":"merchant-final-001","customer_id":"customer-final-001","transaction_type":"PAYMENT","settlement_id":"settle-final-001","amount":{"value":1250.50,"currency":"USD"},"status":"COMPLETED","description":"Final matching test"}}}

#### Bank Transaction

{"event_id":"evt-final-bank-001","event_timestamp":"2026-09-30T10:30:05Z","source":"BANK","data":{"transaction":{"transaction_id":"txn-final-001","reference_id":"ref-final-001","merchant_id":"merchant-final-001","customer_id":"customer-final-001","transaction_type":"PAYMENT","settlement_id":"settle-final-001","amount":{"value":1250.50,"currency":"USD"},"status":"SUCCESS","description":"Final matching test"}}}

### Stop Producer

Ctrl + C

---

## Verify PostgreSQL

docker exec -it reconiq-postgres psql -U reconiq_user -d reconiq_db -c "SELECT event_id, transaction_id, source, amount_value, amount_currency, canonical_status, processed_at FROM normalization.canonical_transactions WHERE transaction_id='txn-final-001';"

---

## Check Result

Check result in first terminal.