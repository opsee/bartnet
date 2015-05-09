FROM develar/java

RUN apk add --update bash

EXPOSE 8080

RUN mkdir /bartnet /bartnet/bin /bartnet/etc /bartnet/lib

WORKDIR /bartnet

COPY bin/* bin/
COPY lib/* lib/
COPY etc/* etc/

ENTRYPOINT ["bin/bartnet"]
CMD ["server", "etc/config.json"]
