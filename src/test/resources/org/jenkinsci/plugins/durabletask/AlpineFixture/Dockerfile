FROM jenkinsci/slave:3.19-1-alpine
USER root
RUN apk add --update --no-cache openssh
RUN ssh-keygen -A
RUN adduser -D test -h /home/test && \
    mkdir -p /home/test/.ssh && \
    echo "test:test" | chpasswd && \
    chown -R test:test /home/test
ENTRYPOINT ["/usr/sbin/sshd", "-D", "-e"]
