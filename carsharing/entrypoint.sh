#!/bin/bash
while true; do 
    PROVIDER=opendatahub
    python -m x2gbfs.x2gbfs -p $PROVIDER -b ${CARSHARING_BASEURL}

    cat << EOF > ./out/manifest.json
    {
      "last_updated": "$(date -Iseconds)",
      "ttl": 0,
      "version": "3.0",
      "data": {
      "versions": [{
          "version": "$(sed -n 's/"version"[: ]\+"\([^"]\+\)"/\1/p' out/$PROVIDER/gbfs.json)",
          "url": "$CARSHARING_BASEURL/$PROVIDER/gbfs.json"
        }
      ]}
    }
EOF
    sleep 60
done