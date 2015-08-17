# Bartnet

Monolothic Clojure API Server

Firstly you'll need to do codegen for protobufs/grpc.  Codegen can be accomplished via protobufs 3 and the grpc-java plugin.  Or just use the build-clj container like so:

```docker pull quay.io/opsee/build-clj && docker run -v `pwd`:/build quay.io/opsee/build-clj ./build.sh nobuild```

Secondly, you need AWS API keys for our snapshot jars.  Set the env variables thusly: LEIN_USERNAME=aws access key id LEIN_PASSPHRASE=aws secret key.

## Testing

```lein midje```

## Building

``` lein uberjar```

## Docker

### Building

```lein docker```

### Running

```docker run p 8080:8080 bartnet```

You can then test against the Bartnet API on port 8080.
