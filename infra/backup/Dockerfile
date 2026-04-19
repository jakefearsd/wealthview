FROM alpine:3.20

RUN apk add --no-cache postgresql16-client

COPY backup.sh /backup.sh
RUN chmod +x /backup.sh

COPY crontab /etc/crontabs/root

RUN mkdir -p /backups

CMD ["crond", "-f", "-l", "2"]
