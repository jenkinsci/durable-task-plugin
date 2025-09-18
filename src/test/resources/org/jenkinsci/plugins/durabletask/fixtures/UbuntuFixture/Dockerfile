FROM ubuntu:noble
RUN apt-get update -y && \
    apt-get install -y \
        openjdk-17-jre-headless \
        openssh-server \
        locales
RUN mkdir -p /var/run/sshd
RUN useradd test -d /home/test && \
    mkdir -p /home/test/.ssh && \
    echo "test:test" | chpasswd && \
    chown -R test:test /home/test
ENTRYPOINT ["/usr/sbin/sshd", "-D", "-e"]
