FROM jenkins/agent:alpine-jdk11
USER root
RUN apk add --upgrade --update --no-cache openssh
RUN ssh-keygen -A
RUN adduser -D test -h /home/test && \
    mkdir -p /home/test/.ssh && \
    echo "test:test" | chpasswd && \
    chown -R test:test /home/test
ENTRYPOINT ["/usr/sbin/sshd", "-D", "-e"]
