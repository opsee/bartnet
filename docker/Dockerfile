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

EXPOSE 8080

ENTRYPOINT ["/bartnet/bin/bartnet"]
CMD ["start"]
