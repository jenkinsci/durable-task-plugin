FROM registry.access.redhat.com/ubi9/ubi
RUN yum -y install \
        openssh-server \
        java-21-openjdk-headless \
        procps-ng \
    && yum clean all
RUN ssh-keygen -A
RUN useradd test -d /home/test && \
    mkdir -p /home/test/.ssh && \
    chown -R test:test /home/test && \
    echo "test:test" | chpasswd
ENTRYPOINT ["/usr/sbin/sshd", "-D", "-e"]
