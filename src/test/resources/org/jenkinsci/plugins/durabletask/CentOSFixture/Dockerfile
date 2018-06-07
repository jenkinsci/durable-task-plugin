FROM centos:7.4.1708
RUN yum -y install \
        openssh-server \
        java-1.8.0-openjdk-headless \
    && yum clean all
RUN ssh-keygen -A
RUN useradd test -d /home/test && \
    mkdir -p /home/test/.ssh && \
    chown -R test:test /home/test && \
    echo "test:test" | chpasswd
ENTRYPOINT ["/usr/sbin/sshd", "-D", "-e"]
