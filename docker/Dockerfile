FROM develar/java

RUN apk add --update bash

EXPOSE 8080

RUN mkdir /bartnet /bartnet/bin /bartnet/etc /bartnet/lib

COPY lib/* /bartnet/lib/
COPY bin/* /bartnet/bin/
COPY etc/* /bartnet/etc/

ENV DB_NAME="postgres"
ENV DB_HOST="postgres"
ENV DB_USER="postgres"
ENV DB_PASS=""
ENV AWS_ACCESS_KEY_ID=""
ENV AWS_SECRET_ACCESS_KEY=""
ENV ENVIRONMENT="dev"

ENTRYPOINT ["/bartnet/bin/bartnet"]
CMD ["start"]
