#!/bin/bash

DOCKER_IMG=551baa9d1b6d
CONTAINER=jupyter-server

start () {
    # check if jupyter is running
    RUNNING=$(docker inspect -f {{.State.Running}} $CONTAINER 2> /dev/null || echo "false")

    if $RUNNING; then
        stop
    fi

    echo "Starting Jupyter server container..."
    docker create -e PYTHONPATH=/etc/jupyter -it --name ${CONTAINER} --rm -p 8001:8001 -p 8002:8002 $DOCKER_IMG
    docker cp jupyter-docker/jupyter_notebook_config.py ${CONTAINER}:/etc/jupyter/jupyter_notebook_config.py
    docker cp /Users/vkumra/Documents/firecloud/leonardo/src/main/resources/jupyter/Jupyter_Server_Extension.py ${CONTAINER}:/etc/jupyter/Jupyter_Server_Extension.py
    docker start ${CONTAINER}

    sleep 5

    echo $(docker logs ${CONTAINER} | grep token)
}

stop() {
    echo "Stopping docker $CONTAINER container..."
    docker stop $CONTAINER 2> /dev/null || echo "${CONTAINER} stop failed. Container already stopped."
    docker rm -v $CONTAINER 2> /dev/null || echo "${CONTAINER} rm -v failed. Container already destroyed."
}

if [ ${#@} == 0 ]; then
    echo "Usage: $0 stop|start"
    exit 1
fi

COMMAND=$1
if [ $COMMAND = "start" ]; then
    start
elif [ $COMMAND = "stop" ]; then
    stop
else
    exit 1
fi
