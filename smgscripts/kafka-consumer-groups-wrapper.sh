#!/bin/bash
# XXX the whole point of this script is to detect if kafka-consumer-groups.sh failed
# and exit accordingly. Apparently kafka-consumer-groups.sh exits with 0 even on failures :/

KAFKA_TIMEOUT=${KAFKA_TIMEOUT:-31}
KAFKA_CONSUMER_GROUPS=${KAFKA_CONSUMER_GROUPS:-"/opt/kafka/bin/kafka-consumer-groups.sh"}

out=$(timeout "$KAFKA_TIMEOUT" "$KAFKA_CONSUMER_GROUPS" "$@" | grep -v '^$')

exit_code=1
if [[ "$out" =~ ^GROUP.* ]]; then
    exit_code=0
fi

echo "$out"
exit $exit_code
