# Bartnet

Monolothic Clojure API Server

Firstly you'll need to do codegen for protobufs/grpc. Protobuf defs are in a git submodule, so be sure to run `git submodule init && git submodule update` on a fresh install. Codegen can be accomplished via protobufs 3 and the grpc-java plugin.  Or just use the build-clj container like so:

```docker pull quay.io/opsee/build-clj && docker run -v `pwd`:/build quay.io/opsee/build-clj ./build.sh nobuild```

Secondly, you need AWS API keys for our snapshot jars.  Set the env variables thusly: LEIN_USERNAME=aws access key id LEIN_PASSPHRASE=aws secret key.

## Building and Testing

`make`

## Docker

### Building

`make docker`

### Running

`make run`

You can then test against the Bartnet API on port 8080. For requests that require authentication (p much all of them), you'll have to send an Authorization header with a bearer token like so:

```Authorization: Bearer thetokengoeshere```

As long as the server is started with the vape key at resources/vape.test.key, you can use the token in test/bartnet/t_api.clj. It's good for a while.
