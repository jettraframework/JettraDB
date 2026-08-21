#!/bin/bash

# Exit on error
set -e

echo "Starting JettraStoreEngine in the background..."
rm -rf data
NODE_ID="127.0.0.1" RAFT_PEERS="127.0.0.1:50051" java -jar target/JettraStoreEngine-1.0-SNAPSHOT.jar > engine.log 2>&1 &
ENGINE_PID=$!

echo "Waiting for Engine to initialize (5s)..."
sleep 5

HOST="http://127.0.0.1:8080"
if grep -q "REST/GraphQL Port: 8082" engine.log; then
  HOST="http://127.0.0.1:8082"
fi

echo "Connecting to $HOST..."

# Login
echo "Logging in..."
LOGIN_RES=$(curl -s -X POST $HOST/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"super-user", "password":"superUserZ"}')

TOKEN=$(echo $LOGIN_RES | grep -o '"token":"[^"]*' | grep -o '[^"]*$')
if [ -z "$TOKEN" ]; then
  echo "Login failed! Response: $LOGIN_RES"
  kill $ENGINE_PID
  exit 1
fi

echo "Token obtained: $TOKEN"

# Testing all 9 models
MODELS=("document" "vector" "graph" "timeseries" "column" "keyvalue" "geospatial" "object" "records")

# Payloads for POST
declare -A PAYLOADS
PAYLOADS=(
  ["document"]='{"_class":"User", "name":"TestUser"}'
  ["vector"]='{"vector":[0.1, 0.2, 0.3]}'
  ["graph"]='{"name":"TestNode"}'
  ["timeseries"]='{"cpu":50.0}'
  ["column"]='{"cf:col":"value"}'
  ["keyvalue"]="raw_string_value"
  ["geospatial"]='{"lat":10.0, "lon":20.0}'
  ["object"]='{"state":"active"}'
  ["records"]='{"_recordClass":"com.jettra.model.PersonRecord", "components":{"id":"123456", "name":"Alice", "age":30}}'
)

for MODEL in "${MODELS[@]}"; do
  echo "---------------------------------"
  echo "Testing $MODEL Engine..."
  
  PAYLOAD=${PAYLOADS[$MODEL]}
  
  # INSERT
  echo "Inserting data..."
  curl -s -X POST $HOST/api/model/$MODEL/test_ns/123456 \
       -H "Authorization: Bearer $TOKEN" \
       -H "Content-Type: application/json" \
       -d "$PAYLOAD"
  echo "Insert request sent."
  
  # GET
  echo "Retrieving data..."
  GET_RES=$(curl -s -X GET $HOST/api/model/$MODEL/test_ns/123456 \
       -H "Authorization: Bearer $TOKEN")
  echo "Result: $GET_RES"
done

echo "---------------------------------"
echo "All tests completed."
echo "Shutting down Engine..."
kill $ENGINE_PID
echo "Done."
