FROM develar/java

RUN apk add --update bash

EXPOSE 8080

RUN mkdir /bartnet /bartnet/bin /bartnet/etc /bartnet/lib

COPY bin/* /bartnet/bin/
COPY target/bartnet*standalone.jar /bartnet/lib/bartnet.jar
COPY etc/* /bartnet/etc/

ENV DB_NAME="postgres"
ENV DB_HOST="postgres"
ENV DB_USER="postgres"
ENV DB_PASS=""

ENTRYPOINT ["/bartnet/bin/bartnet"]
CMD ["start"]
