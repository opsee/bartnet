FROM develar/java

RUN apk add --update bash

EXPOSE 8080

RUN mkdir /bartnet /bartnet/bin /bartnet/etc /bartnet/lib

WORKDIR /bartnet

COPY docker/bin/* bin/
COPY docker/lib/* lib/
COPY docker/etc/* etc/

ENTRYPOINT ["bin/bartnet"]
CMD ["server", "etc/config.json"]
