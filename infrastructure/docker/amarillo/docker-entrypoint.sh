#!/usr/bin/env bash

# amarillo needs started graphopper, so wait some time to let it startup
echo "sleeping to wait for graphhopper startup..."
sleep 300;
echo "sleeping over. graphhopper are you there?"
bash /app/prestart.sh
uvicorn amarillo.main:app --host 0.0.0.0 --port 80;