#!/bin/bash

cd $WORKSPACE_FOLDER_PATHS
PORT=$(cat ".nrepl-port") # this gets created by Calva's Jack-in command

cd "$HOME/.clojure"
clojure -X:mcp :port "$PORT"
