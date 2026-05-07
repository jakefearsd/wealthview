FROM alpine:3.20@sha256:d9e853e87e55526f6b2917df91a2115c36dd7c696a35be12163d44e6e2a4b6bc

RUN apk add --no-cache postgresql16-client

COPY backup.sh /backup.sh
RUN chmod +x /backup.sh

COPY crontab /etc/crontabs/root

RUN mkdir -p /backups

CMD ["crond", "-f", "-l", "2"]
