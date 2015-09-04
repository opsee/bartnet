#!/bin/sh

protoc --plugin=protoc-gen-grpc-java=/usr/local/bin/protoc-gen-grpc-java \
        --proto_path=/usr/local/include \
        --java_out=src --grpc-java_out=src --proto_path=resources/proto resources/proto/checker.proto
