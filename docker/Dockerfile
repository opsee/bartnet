FROM develar/java:8u45

RUN apk add --update bash curl

RUN mkdir /bartnet /bartnet/bin /bartnet/etc /bartnet/lib
RUN mkdir -p /opt/bin
RUN curl -o /opt/bin/ec2-env https://s3-us-west-2.amazonaws.com/opsee-releases/go/ec2-env/ec2-env && \
  chmod 755 /opt/bin/ec2-env
RUN curl -o /opt/bin/s3kms https://s3-us-west-2.amazonaws.com/opsee-releases/go/vinz-clortho/s3kms-linux-amd64 && \
  chmod 755 /opt/bin/s3kms

COPY lib/* /bartnet/lib/
COPY bin/* /bartnet/bin/
COPY etc/* /bartnet/etc/

ENV NSQ_LOOKUP=""
ENV NSQ_PRODUCE=""
ENV FIERI_ADDR=""
ENV VAPE_ADDR=""
ENV BASTION_DNS=""
ENV BASTION_VPN_REMOTE=""
ENV DB_NAME="postgres"
ENV DB_HOST="postgres"
ENV DB_USER="postgres"
ENV DB_PASS=""
ENV BARTNET_HOST="https://bartnet.in.opsee.com"
ENV BASTION_AUTH_TYPE="BASIC_TOKEN"
ENV AWS_ACCESS_KEY_ID=""
ENV AWS_SECRET_ACCESS_KEY=""
ENV AWS_REGION="us-west-1"
ENV AWS_DEFAULT_REGION=""
ENV AWS_INSTANCE_ID=""
ENV AWS_SESSION_TOKEN=""
ENV ENVIRONMENT="dev"
ENV APPENV="bartnetenv"

EXPOSE 8080

ENTRYPOINT ["/bartnet/bin/bartnet"]
CMD ["start"]
