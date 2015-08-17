# Bartnet

Monolothic Clojure API Server

In order to build, you need AWS API keys for our snapshot jars.  Set the env variables thusly: LEIN_USERNAME=aws access key id LEIN_PASSPHRASE=aws secret key.

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
