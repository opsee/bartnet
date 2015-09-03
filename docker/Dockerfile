FROM develar/java:8u45

RUN apk add --update bash curl

RUN mkdir /bartnet /bartnet/bin /bartnet/etc /bartnet/lib
RUN mkdir -p /opt/bin
RUN curl -o /opt/bin/ec2-env https://s3-us-west-2.amazonaws.com/opsee-releases/go/ec2-env/ec2-env
RUN curl -o /opt/bin/s3kms https://s3-us-west-2.amazonaws.com/opsee-releases/go/vinz-clortho/s3kms-linux-amd64
RUN apk del curl

COPY lib/* /bartnet/lib/
COPY bin/* /bartnet/bin/
COPY etc/* /bartnet/etc/

ENV DB_NAME="postgres"
ENV DB_HOST="postgres"
ENV DB_USER="postgres"
ENV DB_PASS=""
ENV AWS_ACCESS_KEY_ID=""
ENV AWS_SECRET_ACCESS_KEY=""
ENV AWS_REGION="us-west-1"
ENV ENVIRONMENT="dev"

EXPOSE 8080
EXPOSE 4080

ENTRYPOINT ["/bartnet/bin/bartnet"]
CMD ["start"]
