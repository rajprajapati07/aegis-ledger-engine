#!/usr/bin/env bash
# Walks through the API end to end against a running service.
# Needs curl and jq.   Usage: ./scripts/smoke.sh [base-url]
set -euo pipefail

BASE="${1:-http://localhost:8080}"
post() { curl -sS -X POST "$BASE$1" -H 'Content-Type: application/json' "${@:3}" -d "$2"; }

echo "== ledger"
LEDGER=$(post /v1/ledgers "{\"name\":\"SMOKE_$(date +%s)\",\"currency\":\"USD\"}" | jq -r .ledger_id)
echo "$LEDGER"

echo "== accounts"
acct() { post /v1/accounts "{\"ledger_id\":\"$LEDGER\",\"type\":\"$1\",\"allow_negative\":$2,\"buckets\":${3:-1}}" | jq -r .account_id; }
RESERVES=$(acct ASSET true)
WALLET=$(acct LIABILITY false)
MERCHANT=$(acct LIABILITY false 16)
echo "reserves=$RESERVES wallet=$WALLET merchant=$MERCHANT"

tx() {
  post /v1/transactions "{\"ledger_id\":\"$LEDGER\",\"entries\":[
      {\"account_id\":\"$2\",\"direction\":\"DEBIT\",\"amount\":\"$4\"},
      {\"account_id\":\"$3\",\"direction\":\"CREDIT\",\"amount\":\"$4\"}]}" \
    -H "Idempotency-Key: $1" -i
}

echo; echo "== the SRS example on a fresh wallet: expect 422 INSUFFICIENT_FUNDS"
tx "srs-$LEDGER" "$WALLET" "$MERCHANT" 150.00 | sed -n '1p;$p'

echo; echo "== fund wallet with 500.00: expect 201"
tx "fund-$LEDGER" "$RESERVES" "$WALLET" 500.00 | sed -n '1p'

echo; echo "== pay merchant 150.00: expect 201"
tx "pay-$LEDGER" "$WALLET" "$MERCHANT" 150.00 | sed -n '1p'

echo; echo "== same key again: expect 200 + Idempotent-Replayed"
tx "pay-$LEDGER" "$WALLET" "$MERCHANT" 150.00 | grep -Ei '^HTTP|^idempotent-replayed'

echo; echo "== same key, different amount: expect 409 IDEMPOTENCY_KEY_REUSED"
tx "pay-$LEDGER" "$WALLET" "$MERCHANT" 151.00 | sed -n '1p;$p'

echo; echo "== balances"
curl -sS "$BASE/v1/accounts/$WALLET"   | jq -c '{account_id, balance}'
curl -sS "$BASE/v1/accounts/$MERCHANT" | jq -c '{account_id, balance, buckets}'

echo; echo "== trial balance: expect balanced=true"
curl -sS "$BASE/v1/ledgers/$LEDGER/trial-balance" | jq -c .
