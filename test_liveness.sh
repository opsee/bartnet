#!/bin/bash

BARTNET_HOST=${BARTNET_HOST:-localhost}

function retry {
    n=0
    until [ $n -ge 10 ]
    do
        echo "try ${n}"
        test "$(curl -s --retry 10 --retry-delay 5 http://${BARTNET_HOST}:8080/health_check)" = "A ok" && result=0 && return
        n=$[$n+1]
        sleep 15
    done
    result=1
}

retry

exit $result
