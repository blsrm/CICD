#!/bin/bash

: '
A script to stop and remove the existing container(name) service and then start it
once with an updated docker image 

Note:
    Its implemented only for Linux based remote server(physical)
'

# Exporting the arguments as environment variables, so that it can be
# re-used inside any of the functions block
export CONTAINER_NAME=$1
export DOCKER_IMAGE=$2
export PORT=$3

# Function to get the ID of container name
function show_docker_container_id() {
    echo $(docker ps -f name=${CONTAINER_NAME} -q)
}

# Running docker container
function docker_run() {
    docker run --name $CONTAINER_NAME -d -it -p $PORT:$PORT $DOCKER_IMAGE
    echo "Docker container successfully started with the updated image"
    container_id=$(show_docker_container_id)
    if [ -z "$container_id" ]; then
    	echo "The docker container ${CONTAINER_NAME} is not running."
    	exit 1
    else
	    echo "The docker container ${CONTAINER_NAME} running with the ID: ${container_id}."
    fi
}

# Checking status of the docker container
IS_RUNNING=$(docker inspect --format='{{.State.Running}}' ${CONTAINER_NAME} 2> /dev/null)

if [ -z "$IS_RUNNING" ]; then
    echo "No docker container running with the name ${CONTAINER_NAME}"
else
    echo "Docker container already exists, So now we are proceeding with Stop and removing the existing container"
    docker stop $CONTAINER_NAME && docker rm $CONTAINER_NAME
fi

# Invoking docker_run function to run the docker container
docker_run
