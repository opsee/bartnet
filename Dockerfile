FROM develar/java

RUN apk add --update bash

EXPOSE 8080

RUN mkdir /bartnet /bartnet/bin /bartnet/etc /bartnet/lib

COPY bin/* /bartnet/bin/
COPY target/bartnet*standalone.jar /bartnet/lib/bartnet.jar
COPY etc/* /bartnet/etc/

ENTRYPOINT ["/bartnet/bin/bartnet"]
CMD ["server", "/bartnet/etc/config.json"]
